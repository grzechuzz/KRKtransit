package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.support.Endpoints;
import pl.krktransit.realtime.ingestion.support.Observations;

@Timeout(20)
class RedisObservationSinkTest {
    private static final String PREFIX = "observations";
    private static final StreamRoutes ROUTES = new StreamRoutes(PREFIX,
            Map.of(Endpoints.MPK_BUS, 100L, Endpoints.MPK_TRAM, 200L, Endpoints.MOBILIS_BUS, 300L));
    private final Deque<FakeWriter> prepared = new ArrayDeque<>();
    private final List<FakeWriter> connected = new CopyOnWriteArrayList<>();
    private final PublicationStatistics statistics = new PublicationStatistics();
    private final List<Throwable> crashes = new CopyOnWriteArrayList<>();
    private int unavailableConnections;
    private RedisObservationSink sink;

    @AfterEach
    void closeSink() {
        if (sink != null) sink.close();
        assertThat(crashes).isEmpty();
    }

    @Test
    void appendsInAdmissionOrderToEachSourceStreamWithItsCap() {
        start(1 << 20, 100);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a"), Observations.tripUpdate(Endpoints.MPK_BUS, "b")));
        sink.offer(List.of(Observations.vehicle(Endpoints.MOBILIS_BUS, "c")));
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "d")));

        await().until(() -> writer().appends.size() == 4);

        var appends = writer().appends;
        assertThat(appends).extracting(append -> new String(append.target().key(), StandardCharsets.UTF_8))
                .containsExactly("observations:krakow:mpk-bus", "observations:krakow:mpk-bus",
                        "observations:krakow:mobilis-bus", "observations:krakow:mpk-bus");
        assertThat(appends).extracting(append -> append.target().maxLength()).containsExactly(100L, 100L, 300L, 100L);
        assertThat(appends).extracting(append -> tripId(append.payload())).containsExactly("a", "b", "c", "d");
        await().until(() -> sink.pendingBytes() == 0);
        assertThat(statistics.drain().published()).isEqualTo(4);
    }

    @Test
    void discardsWholeResultsThatDoNotFitAndKeepsAdmittedOnes() {
        prepared.add(new FakeWriter(false));
        start(encodedSize(Observations.vehicle(Endpoints.MPK_BUS, "a")) * 2L, 100);

        assertThat(sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a")))).isTrue();
        assertThat(sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "b"),
                Observations.vehicle(Endpoints.MPK_BUS, "c")))).isFalse();

        var snapshot = statistics.drain();
        assertThat(snapshot.overflowResults()).isEqualTo(1);
        assertThat(snapshot.overflowRecords()).isEqualTo(2);
        await().until(() -> writer().appends.size() == 1);
    }

    @Test
    void acquisitionResumesOnlyAfterTheReplyReleasesCapacity() throws Exception {
        var observation = Observations.vehicle(Endpoints.MPK_BUS, "a");
        prepared.add(new FakeWriter(false));
        start(encodedSize(observation), 100);
        sink.offer(List.of(observation));
        await().until(() -> writer().appends.size() == 1);

        var waiting = CompletableFuture.runAsync(this::awaitCapacity);
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        writer().reply(0);

        waiting.get(5, TimeUnit.SECONDS);
        assertThat(statistics.drain().published()).isEqualTo(1);
    }

    @Test
    void boundsCommandsAwaitingAReply() throws Exception {
        prepared.add(new FakeWriter(false));
        start(1 << 20, 2);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a"), Observations.vehicle(Endpoints.MPK_BUS, "b"),
                Observations.vehicle(Endpoints.MPK_BUS, "c")));

        await().until(() -> writer().appends.size() == 2);
        Thread.sleep(100);
        assertThat(writer().appends).hasSize(2);

        writer().reply(0);
        await().until(() -> writer().appends.size() == 3);
    }

    @Test
    void redisRejectionIsDroppedAndAMissingReplyIsUnconfirmedOnAFreshConnection() {
        prepared.add(new FakeWriter(false));
        start(1 << 20, 100);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a"), Observations.vehicle(Endpoints.MPK_BUS, "b"),
                Observations.vehicle(Endpoints.MPK_BUS, "c")));
        await().until(() -> writer().appends.size() == 3);
        var first = writer();

        first.fail(0, new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'"));
        first.fail(1, new RedisCommandTimeoutException("Command timed out after 10 second(s)"));
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "d")));

        await().until(() -> connected.size() == 2 && writer().appends.size() == 1);
        assertThat(first.closed).isTrue();
        assertThat(tripId(writer().appends.getFirst().payload())).isEqualTo("d");
        await().until(() -> sink.pendingBytes() == 0);
        var snapshot = statistics.drain();
        assertThat(snapshot.dropped()).isEqualTo(1);
        assertThat(snapshot.unconfirmed()).isEqualTo(2);
        assertThat(snapshot.published()).isEqualTo(1);
        assertThat(snapshot.lastFailure()).isNotNull();
    }

    @Test
    void executionErrorsKeepTheConnection() {
        prepared.add(new FakeWriter(false));
        start(1 << 20, 100);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a")));
        await().until(() -> writer().appends.size() == 1);

        writer().fail(0, new RedisCommandExecutionException("NOPERM this user has no permissions"));
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "b")));

        await().until(() -> writer().appends.size() == 2);
        assertThat(connected).hasSize(1);
        assertThat(statistics.drain().dropped()).isEqualTo(1);
    }

    @Test
    void locallyRejectedAppendIsDroppedAndTheNextRecordUsesANewConnection() {
        var rejecting = new FakeWriter(true);
        rejecting.rejection = new RedisException("Currently not connected. Commands are rejected.");
        prepared.add(rejecting);
        start(1 << 20, 100);

        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "lost"), Observations.vehicle(Endpoints.MPK_BUS, "kept")));

        await().until(() -> connected.size() == 2 && writer().appends.size() == 1);
        assertThat(tripId(writer().appends.getFirst().payload())).isEqualTo("kept");
        await().until(() -> sink.pendingBytes() == 0);
        var snapshot = statistics.drain();
        assertThat(snapshot.dropped()).isEqualTo(1);
        assertThat(snapshot.published()).isEqualTo(1);
    }

    @Test
    void closedConnectionIsReplacedAfterRedisBecomesAvailableAgain() {
        start(1 << 20, 100);
        writer().open = false;
        unavailableConnections = 2;

        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_TRAM, "a")));

        await().until(() -> connected.size() == 2 && writer().appends.size() == 1);
        assertThat(unavailableConnections).isZero();
        await().until(() -> sink.pendingBytes() == 0);
        assertThat(statistics.drain().published()).isEqualTo(1);
    }

    @Test
    void lateFailuresFromAReplacedConnectionDoNotDisconnectItsSuccessor() {
        prepared.add(new FakeWriter(false));
        prepared.add(new FakeWriter(false));
        start(1 << 20, 100);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a")));
        await().until(() -> writer().appends.size() == 1);
        var first = writer();
        first.open = false;

        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "b")));
        await().until(() -> connected.size() == 2 && writer().appends.size() == 1);
        first.fail(0, new RedisCommandTimeoutException("late timeout"));
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "c")));

        await().until(() -> writer().appends.size() == 2);
        assertThat(connected).hasSize(2);
    }

    @Test
    void startFailsWhenRedisIsUnavailable() {
        unavailableConnections = 1;
        sink = new RedisObservationSink(ROUTES, this::nextWriter, 1 << 20, 100, Duration.ZERO, Duration.ofSeconds(2),
                statistics);

        assertThatThrownBy(() -> sink.start((thread, failure) -> crashes.add(failure)))
                .isInstanceOf(RedisUnavailable.class);
    }

    @Test
    void closeDrainsQueuedRecordsAndClosesTheConnection() {
        start(1 << 20, 100);
        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a"), Observations.vehicle(Endpoints.MPK_TRAM, "b")));

        sink.close();

        assertThat(writer().appends).hasSize(2);
        assertThat(writer().closed).isTrue();
        assertThat(statistics.drain().shutdownDiscarded()).isZero();
        assertThat(sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "late")))).isFalse();
    }

    @Test
    void shutdownWhileRedisIsUnavailableAccountsForEveryRecord() {
        start(1 << 20, 100, Duration.ofMillis(200), Duration.ofMillis(300));
        writer().open = false;
        unavailableConnections = Integer.MAX_VALUE;

        sink.offer(List.of(Observations.vehicle(Endpoints.MPK_BUS, "a"), Observations.vehicle(Endpoints.MPK_BUS, "b"),
                Observations.vehicle(Endpoints.MPK_BUS, "c")));
        await().until(() -> unavailableConnections < Integer.MAX_VALUE);
        sink.close();

        assertThat(statistics.drain().shutdownDiscarded()).isEqualTo(3);
        assertThat(sink.pendingBytes()).isZero();
    }

    private void start(long bufferBytes, int maxInFlight) {
        start(bufferBytes, maxInFlight, Duration.ZERO, Duration.ofSeconds(2));
    }

    private void start(long bufferBytes, int maxInFlight, Duration recoveryDelay, Duration shutdownDrain) {
        sink = new RedisObservationSink(ROUTES, this::nextWriter, bufferBytes, maxInFlight, recoveryDelay,
                shutdownDrain, statistics);
        sink.start((thread, failure) -> crashes.add(failure));
    }

    private synchronized StreamWriter nextWriter() {
        if (unavailableConnections > 0) {
            unavailableConnections--;
            throw new RedisUnavailable("Redis at test is unavailable", new RedisException("refused"));
        }
        var writer = prepared.isEmpty() ? new FakeWriter(true) : prepared.poll();
        connected.add(writer);
        return writer;
    }

    private FakeWriter writer() {
        return connected.getLast();
    }

    private void awaitCapacity() {
        try {
            sink.awaitCapacity();
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static long encodedSize(RealtimeObservation observation) {
        return new ObservationWireMapper().encode(observation).length
                + StreamRoutes.key(PREFIX, observation.origin().source()).length;
    }

    private static String tripId(byte[] payload) {
        try {
            var observation = Observation.parseFrom(payload);
            return observation.hasVehiclePosition() ? observation.getVehiclePosition().getTripId()
                    : observation.getTripUpdate().getTripId();
        } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class FakeWriter implements StreamWriter {
        private final boolean autoReply;
        private final List<Append> appends = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;
        private volatile boolean closed;
        private volatile RedisException rejection;

        private FakeWriter(boolean autoReply) {
            this.autoReply = autoReply;
        }

        @Override
        public boolean isOpen() {
            return open && !closed;
        }

        @Override
        public CompletionStage<String> append(StreamTarget target, byte[] payload) {
            if (rejection != null) {
                var rejected = rejection;
                rejection = null;
                return CompletableFuture.failedFuture(rejected);
            }
            var append = new Append(target, payload, new CompletableFuture<>());
            appends.add(append);
            if (autoReply) append.reply().complete(appends.size() + "-0");
            return append.reply();
        }

        private void reply(int index) {
            appends.get(index).reply().complete(index + "-0");
        }

        private void fail(int index, RedisException failure) {
            appends.get(index).reply().completeExceptionally(failure);
        }

        @Override
        public void close() {
            closed = true;
            appends.forEach(append -> append.reply().completeExceptionally(new RedisException("Connection closed")));
        }
    }

    private record Append(StreamTarget target, byte[] payload, CompletableFuture<String> reply) {}
}
