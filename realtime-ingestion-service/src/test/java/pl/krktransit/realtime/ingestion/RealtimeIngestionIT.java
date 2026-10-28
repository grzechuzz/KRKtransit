package pl.krktransit.realtime.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.PublicationStatistics;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.RedisObservationSink;
import pl.krktransit.realtime.ingestion.bootstrap.RealtimeIngestionApplication;
import pl.krktransit.realtime.ingestion.support.GtfsRealtimeFeeds;
import pl.krktransit.realtime.ingestion.support.ObservationStreams;
import pl.krktransit.realtime.ingestion.support.ObservationStreams.Entry;
import pl.krktransit.realtime.ingestion.support.RealtimeRedisContainer;

@Testcontainers
@Timeout(240)
class RealtimeIngestionIT {
    @Container
    private static final RealtimeRedisContainer REDIS = new RealtimeRedisContainer();
    private static final List<String> SOURCES = List.of("mpk-bus", "mpk-tram", "mobilis-bus");
    private final String prefix = "observations-" + UUID.randomUUID();
    private final List<String> streams = SOURCES.stream().map(source -> ObservationStreams.key(prefix, source)).toList();
    private final Map<String, byte[]> feeds = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requests = new ConcurrentHashMap<>();
    private MockWebServer server;
    private ConfigurableApplicationContext application;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.computeIfAbsent(request.getTarget(), ignored -> new AtomicLong()).incrementAndGet();
                byte[] body = feeds.get(request.getTarget());
                if (body == null) return new MockResponse.Builder().code(404).build();
                return new MockResponse.Builder().body(new Buffer().write(body)).build();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        if (application != null) application.close();
        server.close();
    }

    @Test
    void collectsEveryHealthyEndpointIntoItsSourceStreamWhileAFailingEndpointBacksOff() {
        for (String source : SOURCES) feeds.put("/" + source + "/vp.pb", GtfsRealtimeFeeds.vehiclePositions(source, 3));
        feeds.put("/mpk-bus/tu.pb", GtfsRealtimeFeeds.tripUpdates("mpk-bus", 2, 4));
        feeds.put("/mpk-tram/tu.pb", GtfsRealtimeFeeds.tripUpdates("mpk-tram", 2, 4));
        Instant started = Instant.now();
        application = start(Duration.ofMillis(300));

        try (var reader = new ObservationStreams(REDIS.ownedUrl())) {
            var entries = reader.readUntil(streams, read -> kinds(read).size() == 5, Duration.ofSeconds(60));

            assertThat(kinds(entries)).containsExactlyInAnyOrder("mpk-bus:vp", "mpk-bus:tu", "mpk-tram:vp",
                    "mpk-tram:tu", "mobilis-bus:vp");
            assertThat(entries).allSatisfy(entry -> assertThat(entry.stream())
                    .isEqualTo(ObservationStreams.key(prefix, entry.observation().getSource().getSourceId())));
            var first = entries.stream().filter(entry -> entry.stream().endsWith(":mpk-tram")).findFirst()
                    .orElseThrow().observation();
            assertThat(first.getSource().getCityId()).isEqualTo("krakow");
            assertThat(Instant.ofEpochSecond(first.getFetchedAt().getSeconds())).isAfterOrEqualTo(started.minusSeconds(1));
            assertThat(first.getFeedTimestamp()).isEqualTo(1_761_387_300L);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> requestsTo("/mpk-bus/vp.pb") >= 6);
        assertThat(requestsTo("/mobilis-bus/tu.pb")).isBetween(1L, 2L);
    }

    @Test
    void measuredPeakVolumeIsPublishedRepeatedlyWithoutLossOrBacklog() throws Exception {
        for (String source : SOURCES) {
            feeds.put("/" + source + "/vp.pb", GtfsRealtimeFeeds.vehiclePositions(source, 640));
            feeds.put("/" + source + "/tu.pb", GtfsRealtimeFeeds.tripUpdates(source, 1230, 15));
        }
        application = start(Duration.ofSeconds(4));
        var statistics = application.getBean(PublicationStatistics.class);
        var sink = application.getBean(RedisObservationSink.class);
        int perCycle = 3 * (640 + 1230);

        List<Entry> entries;
        try (var reader = new ObservationStreams(REDIS.ownedUrl())) {
            await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> streams.stream().mapToLong(reader::length).sum() >= 5L * perCycle);
            var snapshot = statistics.drain();
            application.close();
            application = null;
            long readStarted = System.nanoTime();
            entries = reader.readAll(streams);
            long readMillis = Duration.ofNanos(System.nanoTime() - readStarted).toMillis();

            var appendLatencies = sorted(entries.stream().map(RealtimeIngestionIT::fetchToAppendMillis).toList());
            System.out.printf("Load: %d entries, %d published, pending %d bytes%n", entries.size(),
                    snapshot.published(), sink.pendingBytes());
            System.out.printf("Load: fetch completion -> Redis append p50=%dms p95=%dms max=%dms%n",
                    percentile(appendLatencies, 0.5), percentile(appendLatencies, 0.95), appendLatencies.getLast());
            fetchSpans(entries).forEach((kind, spans) -> System.out.printf(
                    "Load: %s fetch first->last Redis append over %d fetches p50=%dms max=%dms%n",
                    kind, spans.size(), percentile(spans, 0.5), spans.getLast()));
            System.out.printf("Load: fresh reader read+decoded %d entries in %dms (%d entries/s)%n", entries.size(),
                    readMillis, entries.size() * 1000L / Math.max(1, readMillis));
            assertThat(entries.size()).isGreaterThanOrEqualTo(5 * perCycle);
            assertThat(snapshot.hasLoss()).as("publication loss: %s", snapshot).isFalse();
            assertThat(percentile(appendLatencies, 0.95)).isLessThan(4000L);
        }
    }

    private static long fetchToAppendMillis(Entry entry) {
        var fetchedAt = entry.observation().getFetchedAt();
        return entry.appendMillis() - (fetchedAt.getSeconds() * 1000 + fetchedAt.getNanos() / 1_000_000);
    }

    private static Map<String, List<Long>> fetchSpans(List<Entry> entries) {
        Map<String, List<Entry>> byFetch = entries.stream().collect(Collectors.groupingBy(entry -> {
            var observation = entry.observation();
            return (observation.hasVehiclePosition() ? "VP" : "TU") + "|" + entry.stream() + "|" + observation.getFetchedAt();
        }));
        return byFetch.entrySet().stream().collect(Collectors.groupingBy(entry -> entry.getKey().substring(0, 2),
                TreeMap::new, Collectors.collectingAndThen(Collectors.mapping(entry -> span(entry.getValue()),
                        Collectors.toList()), RealtimeIngestionIT::sorted)));
    }

    private static long span(List<Entry> entries) {
        var appended = entries.stream().mapToLong(Entry::appendMillis).summaryStatistics();
        return appended.getMax() - appended.getMin();
    }

    private static List<Long> sorted(List<Long> values) {
        return values.stream().sorted().toList();
    }

    private static long percentile(List<Long> sorted, double fraction) {
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction)));
    }

    private ConfigurableApplicationContext start(Duration pollDelay) {
        var arguments = new ArrayList<>(List.of(
                "--realtime.redis.url=" + REDIS.ownedUrl(),
                "--realtime.redis.stream-prefix=" + prefix,
                "--realtime.poll-delay=" + pollDelay.toMillis() + "ms",
                "--realtime.report-interval=1h"));
        for (int index = 0; index < SOURCES.size(); index++) {
            String source = SOURCES.get(index);
            String option = "--realtime.sources[" + index + "].";
            arguments.addAll(List.of(option + "city-id=krakow", option + "source-id=" + source,
                    option + "stream-max-length=150000",
                    option + "vehicle-positions-url=" + server.url("/" + source + "/vp.pb"),
                    option + "trip-updates-url=" + server.url("/" + source + "/tu.pb")));
        }
        return new SpringApplicationBuilder(RealtimeIngestionApplication.class).run(arguments.toArray(String[]::new));
    }

    private static Set<String> kinds(List<Entry> entries) {
        return entries.stream().map(entry -> entry.observation().getSource().getSourceId() + ":"
                + (entry.observation().hasVehiclePosition() ? "vp" : "tu")).collect(Collectors.toSet());
    }

    private long requestsTo(String path) {
        return requests.getOrDefault(path, new AtomicLong()).get();
    }
}
