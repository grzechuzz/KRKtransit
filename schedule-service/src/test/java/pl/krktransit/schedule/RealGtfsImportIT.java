package pl.krktransit.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.schedule.adapter.inbound.scheduling.ImportCycleRunner;
import pl.krktransit.schedule.application.importing.PublicationOutcome;
import pl.krktransit.schedule.bootstrap.MigrationCommand;
import pl.krktransit.schedule.bootstrap.ScheduleApplication;
import pl.krktransit.schedule.contracts.v1.FeedId;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.support.SchedulePostgresContainer;

@Testcontainers
@EnabledIfSystemProperty(named = "schedule.real-feeds", matches = ".+")
class RealGtfsImportIT {
    private static final String ROW_COUNTS_SQL = """
            WITH selected AS (
                SELECT current_version_key AS version_key FROM gtfs_schedule.feeds
                WHERE city_id = ? AND source_id = ?
            )
            SELECT 'routes', count(*) FROM gtfs_schedule.routes JOIN selected USING (version_key)
            UNION ALL SELECT 'stops', count(*) FROM gtfs_schedule.stops JOIN selected USING (version_key)
            UNION ALL SELECT 'trips', count(*) FROM gtfs_schedule.trips JOIN selected USING (version_key)
            UNION ALL SELECT 'stop_times', count(*) FROM gtfs_schedule.stop_times JOIN selected USING (version_key)
            UNION ALL SELECT 'shape_points', count(*) FROM gtfs_schedule.shape_points JOIN selected USING (version_key)
            """;
    private static final List<String> SOURCES = List.of("mpk", "mobilis", "tram");
    private static final Map<String, String> GTFS_FILES = Map.of(
            "routes", "routes.txt", "stops", "stops.txt", "trips", "trips.txt",
            "stop_times", "stop_times.txt", "shape_points", "shapes.txt");

    @Container
    private static final SchedulePostgresContainer POSTGRES = fullSizeDatabase();
    @TempDir Path temporary;

    @Test
    void importsRealArchivesThroughHttpAndCopyAndServesGrpc() throws Exception {
        Path feeds = Path.of(System.getProperty("schedule.real-feeds"));
        List<ArchiveSample> samples = readSamples(feeds);
        MigrationCommand.migrate(POSTGRES.dataSource());

        try (var provider = new ArchiveProvider(feeds); var application = application(provider)) {
            var runner = application.getBean(ImportCycleRunner.class);
            assertThat(runner.runCycle().outcomes()).hasSize(3).allSatisfy(outcome -> {
                assertThat(outcome.failure()).isNull();
                assertThat(outcome.publication()).isEqualTo(PublicationOutcome.PUBLISHED);
            });
            verifyPublishedSchedules(application, samples);
            assertThat(runner.runCycle().outcomes()).hasSize(3).allSatisfy(outcome ->
                    assertThat(outcome.publication()).isEqualTo(PublicationOutcome.UNCHANGED));
        }
        try (var downloads = Files.list(temporary.resolve("downloads"))) {
            assertThat(downloads).isEmpty();
        }
    }

    private List<ArchiveSample> readSamples(Path feeds) throws Exception {
        var samples = new ArrayList<ArchiveSample>();
        for (String source : SOURCES) {
            Path archive = feeds.resolve(source + ".zip");
            assertThat(archive).isRegularFile();
            samples.add(readSample(source, archive));
        }
        return samples;
    }

    private ArchiveSample readSample(String source, Path path) throws Exception {
        var counts = new HashMap<String, Long>();
        var firstRows = new HashMap<String, Map<String, String>>();
        try (var archive = new ZipFile(path.toFile())) {
            for (var file : GTFS_FILES.entrySet()) {
                counts.put(file.getKey(), readTable(archive, file.getKey(), file.getValue(), firstRows));
            }
        }
        return new ArchiveSample(source, hash(path), counts, firstRows);
    }

    private long readTable(ZipFile archive, String table, String filename,
                           Map<String, Map<String, String>> firstRows) throws IOException {
        try (var stream = archive.getInputStream(archive.getEntry(filename));
                var reader = new PushbackReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            int first = reader.read();
            if (first != -1 && first != '\ufeff') reader.unread(first);
            try (var rows = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
                long count = 0;
                for (var row : rows) {
                    if (count++ == 0) firstRows.put(table, row.toMap());
                }
                return count;
            }
        }
    }

    private String hash(Path archive) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(archive), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void verifyPublishedSchedules(ConfigurableApplicationContext application, List<ArchiveSample> samples)
            throws Exception {
        int port = application.getBean(GrpcServerLifecycle.class).getPort();
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        try {
            var client = ScheduleServiceGrpc.newBlockingStub(channel);
            for (var sample : samples) {
                verifyRowCounts(application.getBean(DataSource.class), sample);
                verifyGrpc(client.withDeadlineAfter(30, TimeUnit.SECONDS), sample);
                assertThat(Files.mismatch(temporary.resolve("archives").resolve(sample.hash() + ".zip"),
                        Path.of(System.getProperty("schedule.real-feeds")).resolve(sample.source() + ".zip")))
                        .isEqualTo(-1);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void verifyRowCounts(DataSource database, ArchiveSample sample) throws SQLException {
        var actual = new HashMap<String, Long>();
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(ROW_COUNTS_SQL)) {
            statement.setString(1, "krakow");
            statement.setString(2, sample.source());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) actual.put(rows.getString(1), rows.getLong(2));
            }
        }
        assertThat(actual).isEqualTo(sample.counts());
        System.out.println("Verified real GTFS " + sample.source() + ": " + actual + ", SHA-256 " + sample.hash());
    }

    private void verifyGrpc(ScheduleServiceGrpc.ScheduleServiceBlockingStub client, ArchiveSample sample) {
        var feed = FeedId.newBuilder().setCityId("krakow").setSourceId(sample.source());
        assertThat(client.getCurrentHash(GetCurrentHashRequest.newBuilder().setFeedId(feed).build()).getHash())
                .isEqualTo(sample.hash());
        var expected = sample.firstRows().get("trips");
        var trip = client.getTripContext(GetTripContextRequest.newBuilder().setHash(sample.hash())
                .setTripId(sample.scope(expected.get("trip_id"))).build());
        assertThat(trip.getRouteId()).isEqualTo(sample.scope(expected.get("route_id")));
        assertThat(trip.getHeadsign()).isEqualTo(expected.getOrDefault("trip_headsign", ""));
        assertThat(trip.getStopsList()).isNotEmpty();
        assertThat(trip.getStopsList().stream().map(stop -> stop.getStopSequence()).toList()).isSorted();
        verifyShape(client, sample);
    }

    private void verifyShape(ScheduleServiceGrpc.ScheduleServiceBlockingStub client, ArchiveSample sample) {
        var expected = sample.firstRows().get("shape_points");
        var shape = client.getShape(GetShapeRequest.newBuilder().setHash(sample.hash())
                .setShapeId(sample.scope(expected.get("shape_id"))).build());
        assertThat(shape.getPointsList()).anySatisfy(point -> {
            assertThat(point.getLatitude()).isEqualTo(Double.parseDouble(expected.get("shape_pt_lat")));
            assertThat(point.getLongitude()).isEqualTo(Double.parseDouble(expected.get("shape_pt_lon")));
            assertThat(point.getSequence()).isEqualTo(Integer.parseInt(expected.get("shape_pt_sequence")));
        });
        assertThat(shape.getPointsList().stream().map(point -> point.getSequence()).toList()).isSorted();
    }

    private ConfigurableApplicationContext application(ArchiveProvider provider) {
        var arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.grpc.server.address=127.0.0.1", "--spring.grpc.server.port=0",
                "--schedule.jobs-enabled=false", "--schedule.grpc-token=", "--schedule.download.attempts=1",
                "--schedule.archive-directory=" + temporary.resolve("archives"),
                "--schedule.temp-directory=" + temporary.resolve("downloads")));
        for (int index = 0; index < SOURCES.size(); index++) {
            String prefix = "--schedule.feeds[" + index + "].";
            arguments.add(prefix + "city-id=krakow");
            arguments.add(prefix + "source-id=" + SOURCES.get(index));
            arguments.add(prefix + "static-url=" + provider.url(SOURCES.get(index)));
        }
        return new SpringApplicationBuilder(ScheduleApplication.class).web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private static SchedulePostgresContainer fullSizeDatabase() {
        var database = new SchedulePostgresContainer();
        database.withTmpFs(Map.of("/var/lib/postgresql/data", "rw,size=2g"));
        database.withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(3L * 1024 * 1024 * 1024));
        return database;
    }

    private record ArchiveSample(String source, String hash, Map<String, Long> counts,
                                 Map<String, Map<String, String>> firstRows) {
        String scope(String original) { return "krakow:" + source + ":" + original; }
    }

    private static final class ArchiveProvider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        ArchiveProvider(Path feeds) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String source : SOURCES) {
                Path archive = feeds.resolve(source + ".zip");
                server.createContext("/" + source, exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(200, Files.size(archive));
                        Files.copy(archive, exchange.getResponseBody());
                    }
                });
            }
            server.setExecutor(executor);
            server.start();
        }

        String url(String source) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + source; }

        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
