package pl.krktransit.realtime.ingestion.adapter.inbound.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pl.krktransit.realtime.ingestion.application.collection.CollectEndpoint;
import pl.krktransit.realtime.ingestion.application.collection.DecodedFeed;
import pl.krktransit.realtime.ingestion.application.collection.EndpointBackoff;
import pl.krktransit.realtime.ingestion.application.collection.EntityRejection;
import pl.krktransit.realtime.ingestion.application.collection.FetchFailure;
import pl.krktransit.realtime.ingestion.application.port.FeedDecoder;
import pl.krktransit.realtime.ingestion.application.port.FeedFetcher;
import pl.krktransit.realtime.ingestion.application.port.ObservationSink;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.support.Endpoints;

@Timeout(20)
class EndpointPollersTest {
    private static final Endpoint HEALTHY = Endpoints.vehiclePositions(Endpoints.MPK_BUS);
    private static final Endpoint FAILING = Endpoints.tripUpdates(Endpoints.MPK_BUS);
    private final Map<String, List<Duration>> delays = new ConcurrentHashMap<>();
    private final Map<Endpoint, AtomicInteger> fetches = new ConcurrentHashMap<>();
    private final List<Throwable> crashes = new CopyOnWriteArrayList<>();
    private final List<Object> rejectionReports = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger(EndpointPollers.class.getName());
    private final Handler rejectionCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getMessage().contains("rejected entities")) rejectionReports.add(record.getParameters()[1]);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private EndpointPollers pollers;

    @BeforeEach
    void captureRejectionReports() {
        log.addHandler(rejectionCapture);
    }

    @AfterEach
    void stop() {
        if (pollers != null) pollers.close();
        log.removeHandler(rejectionCapture);
    }

    @Test
    void endpointsPollIndependentlyWithTheirOwnBackoff() {
        FeedFetcher fetcher = endpoint -> {
            fetches.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet();
            if (endpoint.equals(FAILING)) throw new FetchFailure(FetchFailure.Kind.TRANSPORT, "refused", null);
            return new byte[0];
        };
        pollers = pollers(fetcher, new OpenSink(), 4);

        pollers.start((thread, failure) -> crashes.add(failure));

        await().until(() -> delaysOf(HEALTHY).size() == 4 && delaysOf(FAILING).size() == 4);
        assertThat(delaysOf(HEALTHY)).containsOnly(Duration.ofSeconds(4));
        assertThat(delaysOf(FAILING)).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30));
        assertThat(crashes).isEmpty();
    }

    @Test
    void acquisitionPausesWhileTheHandoffHasNoCapacity() throws Exception {
        var capacity = new CountDownLatch(1);
        FeedFetcher fetcher = endpoint -> {
            fetches.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet();
            return new byte[0];
        };
        ObservationSink blocked = new OpenSink() {
            @Override
            public void awaitCapacity() throws InterruptedException { capacity.await(); }
        };
        pollers = new EndpointPollers(List.of(HEALTHY), collect(fetcher, blocked), blocked, this::backoff,
                Duration.ofSeconds(5), recordingSleeper(1));

        pollers.start((thread, failure) -> crashes.add(failure));
        Thread.sleep(200);
        assertThat(fetches).isEmpty();
        capacity.countDown();

        await().until(() -> fetches.containsKey(HEALTHY));
    }

    @Test
    void rejectionsAreReportedWhenANewSetAppearsButPollsWithoutRejectionsDoNotResetIt() {
        var sequence = List.of(Map.of(EntityRejection.MISSING_TIMESTAMP, 1), Map.<EntityRejection, Integer>of(),
                Map.of(EntityRejection.MISSING_TIMESTAMP, 2), Map.of(EntityRejection.MISSING_VEHICLE_IDENTITY, 1));
        var polls = new AtomicInteger();
        FeedDecoder decoder = feed -> new DecodedFeed(List.of(), sequence.get(polls.getAndIncrement()));
        var collect = new CollectEndpoint(endpoint -> new byte[0], decoder, new OpenSink(), Clock.systemUTC());
        pollers = new EndpointPollers(List.of(HEALTHY), collect, new OpenSink(), this::backoff, Duration.ofSeconds(5),
                recordingSleeper(sequence.size()));

        pollers.start((thread, failure) -> crashes.add(failure));

        await().until(() -> delaysOf(HEALTHY).size() == sequence.size());
        assertThat(rejectionReports).containsExactly(Map.of(EntityRejection.MISSING_TIMESTAMP, 1),
                Map.of(EntityRejection.MISSING_VEHICLE_IDENTITY, 1));
    }

    @Test
    void closeInterruptsSleepingAndWaitingLoops() {
        pollers = new EndpointPollers(List.of(HEALTHY, FAILING), collect(endpoint -> new byte[0], new OpenSink()),
                new OpenSink(), this::backoff, Duration.ofSeconds(5));
        pollers.start((thread, failure) -> crashes.add(failure));

        long started = System.nanoTime();
        pollers.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(crashes).isEmpty();
    }

    @Test
    void unexpectedFailuresReachTheCrashHandler() {
        FeedFetcher fetcher = endpoint -> { throw new IllegalStateException("bug"); };
        pollers = pollers(fetcher, new OpenSink(), 1);

        pollers.start((thread, failure) -> crashes.add(failure));

        await().until(() -> crashes.size() == 2);
        assertThat(crashes).allSatisfy(failure -> assertThat(failure).hasMessage("bug"));
    }

    private EndpointPollers pollers(FeedFetcher fetcher, ObservationSink sink, int sleepsPerEndpoint) {
        return new EndpointPollers(List.of(HEALTHY, FAILING), collect(fetcher, sink), sink, this::backoff,
                Duration.ofSeconds(5), recordingSleeper(sleepsPerEndpoint));
    }

    private static CollectEndpoint collect(FeedFetcher fetcher, ObservationSink sink) {
        return new CollectEndpoint(fetcher, feed -> new DecodedFeed(List.of(), Map.of()), sink, Clock.systemUTC());
    }

    private EndpointBackoff backoff() {
        return new EndpointBackoff(Duration.ofSeconds(4), List.of(Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30)), 0, RandomGenerator.getDefault());
    }

    private EndpointPollers.Sleeper recordingSleeper(int sleepsPerEndpoint) {
        return delay -> {
            var recorded = delays.computeIfAbsent(Thread.currentThread().getName(), ignored -> new CopyOnWriteArrayList<>());
            recorded.add(delay);
            if (recorded.size() >= sleepsPerEndpoint) Thread.sleep(Duration.ofMinutes(1));
        };
    }

    private List<Duration> delaysOf(Endpoint endpoint) {
        return delays.getOrDefault("realtime-poll-" + endpoint, List.of());
    }

    private static class OpenSink implements ObservationSink {
        @Override
        public void awaitCapacity() throws InterruptedException {}

        @Override
        public boolean offer(List<RealtimeObservation> observations) { return true; }
    }
}
