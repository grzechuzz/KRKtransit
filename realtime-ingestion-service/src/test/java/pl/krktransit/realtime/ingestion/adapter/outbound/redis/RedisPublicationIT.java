package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;
import pl.krktransit.realtime.ingestion.support.Endpoints;
import pl.krktransit.realtime.ingestion.support.ObservationStreams;
import pl.krktransit.realtime.ingestion.support.Observations;
import pl.krktransit.realtime.ingestion.support.RealtimeRedisContainer;

@Testcontainers
@Timeout(180)
class RedisPublicationIT {
    @Container
    private static final RealtimeRedisContainer REDIS = new RealtimeRedisContainer();
    private static final List<SourceId> SOURCES = List.of(Endpoints.MPK_BUS, Endpoints.MPK_TRAM, Endpoints.MOBILIS_BUS);
    private final String prefix = "observations-" + UUID.randomUUID();
    private final PublicationStatistics statistics = new PublicationStatistics();
    private final List<Throwable> crashes = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private RedisObservationSink sink;

    @AfterEach
    void close() throws Exception {
        if (sink != null) sink.close();
        for (AutoCloseable resource : resources.reversed()) resource.close();
        assertThat(crashes).isEmpty();
    }

    @Test
    void eachSourceKeepsItsOwnStreamAndAdmissionOrder() {
        start(clients(null, null, Duration.ofSeconds(10)), 150_000);
        for (int batch = 0; batch < 50; batch++) {
            for (SourceId source : SOURCES) offerAccepted(batch(source, batch * 20, 20));
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> sink.pendingBytes() == 0);

        try (var streams = streams()) {
            for (SourceId source : SOURCES) {
                var entries = streams.read(key(source));
                assertThat(entries).allSatisfy(entry -> assertThat(entry.observation().getSource().getSourceId())
                        .isEqualTo(source.sourceId()));
                assertThat(sequence(entries)).containsExactlyElementsOf(IntStream.range(0, 1000).boxed().toList());
            }
        }
        assertThat(statistics.drain().published()).isEqualTo(3000);
    }

    @Test
    void approximateTrimmingKeepsEachStreamNearItsCapAndRetainsTheNewestEntries() {
        start(clients(null, null, Duration.ofSeconds(10)), 100);
        for (int first = 0; first < 2000; first += 100) offerAccepted(batch(Endpoints.MPK_BUS, first, 100));
        await().atMost(Duration.ofSeconds(60)).until(() -> sink.pendingBytes() == 0);

        try (var streams = streams()) {
            var entries = streams.read(key(Endpoints.MPK_BUS));
            assertThat(entries.size()).isBetween(100, 200);
            assertThat(sequence(entries).getLast()).isEqualTo(1999);
        }
    }

    @Test
    void pausedRedisLeavesSentRecordsUnconfirmedAndHeldRecordsFollowInOrder() {
        start(clients(null, null, Duration.ofSeconds(2)), 150_000);
        offerAccepted(batch(Endpoints.MPK_BUS, 0, 50));
        await().atMost(Duration.ofSeconds(30)).until(() -> sink.pendingBytes() == 0);
        assertThat(statistics.drain().published()).isEqualTo(50);

        REDIS.pause();
        try {
            offerAccepted(batch(Endpoints.MPK_BUS, 50, 50));
            await().pollDelay(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(10)).until(() -> true);
        } finally {
            REDIS.unpause();
        }
        offerAccepted(batch(Endpoints.MPK_BUS, 100, 50));
        await().atMost(Duration.ofSeconds(60)).until(() -> sink.pendingBytes() == 0);
        var outage = statistics.drain();

        try (var streams = streams()) {
            var sequence = sequence(streams.read(key(Endpoints.MPK_BUS)));
            assertThat(outage.unconfirmed()).isPositive();
            assertThat(outage.dropped()).isZero();
            assertThat(sequence).isSorted().doesNotHaveDuplicates();
            assertThat(sequence).containsAll(IntStream.range(0, 50).boxed().toList());
            assertThat(sequence).containsAll(IntStream.range(100, 150).boxed().toList());
        }
    }

    @Test
    void outOfMemoryRejectionsAreDroppedAndPublicationContinuesAfterMemoryIsFreed() {
        start(clients(null, null, Duration.ofSeconds(10)), 150_000);
        try (var streams = streams()) {
            config(streams, "maxmemory-policy", "noeviction");
            config(streams, "maxmemory", "2mb");
            try {
                for (int first = 0; first < 20_000; first += 1000) offerAccepted(batch(Endpoints.MPK_BUS, first, 1000));
                await().atMost(Duration.ofSeconds(60)).until(() -> sink.pendingBytes() == 0);
            } finally {
                config(streams, "maxmemory", "0");
            }
            var rejected = statistics.drain();
            assertThat(rejected.dropped()).isPositive();
            assertThat(rejected.unconfirmed()).isZero();
            assertThat(rejected.lastFailure()).contains("OOM");

            offerAccepted(batch(Endpoints.MPK_TRAM, 0, 10));
            await().atMost(Duration.ofSeconds(30)).until(() -> sink.pendingBytes() == 0);
            assertThat(statistics.drain().published()).isEqualTo(10);
            assertThat(streams.length(key(Endpoints.MPK_TRAM))).isEqualTo(10);
        }
    }

    @Test
    void publisherNeedsOnlyAppendAccessToObservationStreams() {
        try (var streams = streams()) {
            var user = "ingestion-" + UUID.randomUUID();
            acl(streams, "SETUSER", user, "on", ">secret", "resetkeys", "~" + prefix + ":*", "resetchannels",
                    "-@all", "+xadd", "+ping", "+hello", "+client|setinfo", "+client|setname");
            start(clients(user, "secret", Duration.ofSeconds(10)), 150_000);

            offerAccepted(batch(Endpoints.MOBILIS_BUS, 0, 10));
            await().atMost(Duration.ofSeconds(30)).until(() -> sink.pendingBytes() == 0);

            assertThat(statistics.drain().published()).isEqualTo(10);
            assertThat(streams.length(key(Endpoints.MOBILIS_BUS))).isEqualTo(10);
        }
    }

    private void start(RedisClients clients, long maxLength) {
        var routes = new StreamRoutes(prefix, Map.of(Endpoints.MPK_BUS, maxLength, Endpoints.MPK_TRAM, maxLength,
                Endpoints.MOBILIS_BUS, maxLength));
        sink = new RedisObservationSink(routes, clients, 64L * 1024 * 1024, 1000, Duration.ofSeconds(1),
                Duration.ofSeconds(10), statistics);
        sink.start((thread, failure) -> crashes.add(failure));
    }

    private RedisClients clients(String username, String password, Duration commandTimeout) {
        var clients = new RedisClients(REDIS.ownedUrl(), username, password, "realtime-it", Duration.ofSeconds(2),
                commandTimeout);
        resources.add(clients);
        return clients;
    }

    private static ObservationStreams streams() {
        return new ObservationStreams(REDIS.ownedUrl());
    }

    private String key(SourceId source) {
        return prefix + ":" + source;
    }

    private void offerAccepted(List<RealtimeObservation> observations) {
        assertThat(sink.offer(observations)).isTrue();
    }

    private static List<RealtimeObservation> batch(SourceId source, int first, int count) {
        var batch = new ArrayList<RealtimeObservation>();
        for (int index = first; index < first + count; index++) {
            batch.add(index % 2 == 0 ? Observations.vehicle(source, Integer.toString(index))
                    : Observations.tripUpdate(source, Integer.toString(index)));
        }
        return batch;
    }

    private static List<Integer> sequence(List<ObservationStreams.Entry> entries) {
        return entries.stream().map(ObservationStreams.Entry::tripId).map(Integer::parseInt).toList();
    }

    private static void config(ObservationStreams streams, String parameter, String value) {
        streams.commands().configSet(parameter, value);
    }

    private static void acl(ObservationStreams streams, String... arguments) {
        var args = new CommandArgs<>(ByteArrayCodec.INSTANCE);
        for (String argument : arguments) args.add(argument);
        streams.commands().dispatch(CommandType.ACL, new StatusOutput<>(ByteArrayCodec.INSTANCE), args);
    }
}
