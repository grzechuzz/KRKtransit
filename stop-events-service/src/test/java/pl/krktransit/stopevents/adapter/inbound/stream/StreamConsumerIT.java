package pl.krktransit.stopevents.adapter.inbound.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import com.google.protobuf.Timestamp;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import pl.krktransit.realtime.contracts.v1.*;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.support.OwnedRedis;

@Testcontainers
class StreamConsumerIT {
    @Container
    private static final OwnedRedis REDIS = new OwnedRedis();
    private RedisClient client;
    private String prefix;
    private StreamConsumer first;
    private StreamConsumer second;

    @BeforeEach
    void prepare() {
        client = RedisClient.create(REDIS.ownedUrl());
        prefix = "krktransit:realtime:test:" + UUID.randomUUID();
        var sources = Set.of(SOURCE, new SourceId("krakow", "mpk-tram"), new SourceId("krakow", "mobilis-bus"));
        first = new StreamConsumer(() -> REDIS.connect(client), prefix, sources, 500, Duration.ofMillis(20));
        second = new StreamConsumer(() -> REDIS.connect(client), prefix, sources, 500, Duration.ofMillis(20));
    }

    @AfterEach
    void close() { first.close(); second.close(); client.shutdown(); }

    @Test
    void independentReadersSeeAllThreeStreamsInAppendOrder() {
        var sources = List.of(SOURCE, new SourceId("krakow", "mpk-tram"), new SourceId("krakow", "mobilis-bus"));
        var cursors = new HashMap<SourceId, String>();
        try (var raw = REDIS.connect(client)) {
            sources.forEach(source -> {
                cursors.put(source, "0-0");
                raw.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "first")));
                raw.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "second")));
            });
        }
        var read = first.read(cursors);
        assertThat(read).hasSize(3);
        assertThat(read).allSatisfy(batch -> assertThat(batch.entries()).extracting(entry -> entry.observation().tripId())
                .containsExactly(batch.source().scope("first"), batch.source().scope("second")));
        assertThat(second.read(cursors)).isEqualTo(read);
    }

    @Test
    void tailIsConcreteAndMalformedEntriesCanAdvanceProgress() {
        assertThat(first.tail(SOURCE)).isEqualTo("0-0");
        String id;
        try (var raw = REDIS.connect(client)) {
            id = raw.sync().xadd(prefix + ":" + SOURCE, Map.of("payload", new byte[]{-1}));
        }
        assertThat(first.tail(SOURCE)).isEqualTo(id);
        var batch = first.read(Map.of(SOURCE, "0-0")).getFirst();
        assertThat(batch.entries().getFirst().observation()).isNull();
        assertThat(batch.endCursor()).isEqualTo(id);
        assertThat(first.read(Map.of(SOURCE, id))).isEmpty();
    }

    private byte[] observation(SourceId source, String trip) {
        return Observation.newBuilder().setSource(Source.newBuilder().setCityId(source.cityId()).setSourceId(source.sourceId()))
                .setFetchedAt(Timestamp.newBuilder().setSeconds(BASE.getEpochSecond()))
                .setTripUpdate(TripUpdate.newBuilder().setTripId(trip)).build().toByteArray();
    }
}
