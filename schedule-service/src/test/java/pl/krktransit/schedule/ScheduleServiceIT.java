package pl.krktransit.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.schedule.adapter.inbound.scheduling.ImportCycleRunner;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.importing.PublicationOutcome;
import pl.krktransit.schedule.bootstrap.ScheduleApplication;
import pl.krktransit.schedule.contracts.v1.FeedId;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.contracts.v1.ShapePoint;
import pl.krktransit.schedule.support.GtfsFixtures;
import pl.krktransit.schedule.support.SchedulePostgresContainer;

@Testcontainers
@Timeout(60)
class ScheduleServiceIT {
    private static final String TOKEN = "disposable-service-token";
    private static final String RUNTIME_USER = "schedule_runtime_test";
    private static final String RUNTIME_PASSWORD = "disposable-runtime-only";
    private static final String CREATE_RUNTIME_ROLE_SQL =
            "CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'";
    private static final String GRANT_DATABASE_ACCESS_SQL =
            "GRANT CONNECT ON DATABASE krk_schedule_test TO " + RUNTIME_USER;
    private static final String GRANT_SCHEMA_ACCESS_SQL = "GRANT USAGE ON SCHEMA gtfs_schedule TO " + RUNTIME_USER;
    private static final String GRANT_TABLE_ACCESS_SQL = """
            GRANT SELECT, INSERT, UPDATE, DELETE ON gtfs_schedule.feeds,
            gtfs_schedule.schedule_versions, gtfs_schedule.routes, gtfs_schedule.stops,
            gtfs_schedule.trips, gtfs_schedule.stop_times, gtfs_schedule.shape_points
            TO schedule_runtime_test
            """;
    private static final String GRANT_SEQUENCE_ACCESS_SQL =
            "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA gtfs_schedule TO " + RUNTIME_USER;
    private static final String RESET_SCHEDULE_SQL = "TRUNCATE TABLE gtfs_schedule.feeds CASCADE";
    private static final List<String> SOURCES = List.of("mpk", "mobilis", "tram");

    @Container
    private static final SchedulePostgresContainer POSTGRES = new SchedulePostgresContainer();
    @TempDir Path temporary;

    @BeforeAll
    static void migratePackagedApplicationAndCreateRuntimeRole(@TempDir Path outputDirectory) throws Exception {
        Path executable = Path.of("target", "schedule-service-0.1.0.jar").toAbsolutePath();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", executable.toString(), "--migrate");
        builder.environment().put("SCHEDULE_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("SCHEDULE_DB_USER", POSTGRES.getUsername());
        builder.environment().put("SCHEDULE_DB_PASSWORD", POSTGRES.getPassword());
        Path output = outputDirectory.resolve("migration.log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("packaged migration terminated").isTrue();
            assertThat(process.exitValue()).withFailMessage("Migration failed:%n%s", Files.readString(output)).isZero();
        } finally {
            process.destroyForcibly();
        }
        try (var connection = POSTGRES.dataSource().getConnection(); var sql = connection.createStatement()) {
            sql.execute(CREATE_RUNTIME_ROLE_SQL);
            sql.execute(GRANT_DATABASE_ACCESS_SQL);
            sql.execute(GRANT_SCHEMA_ACCESS_SQL);
            sql.execute(GRANT_TABLE_ACCESS_SQL);
            sql.execute(GRANT_SEQUENCE_ACCESS_SQL);
        }
    }

    @BeforeEach
    void resetOwnedDatabase() throws SQLException {
        try (var connection = POSTGRES.dataSource().getConnection(); var sql = connection.createStatement()) {
            sql.execute(RESET_SCHEDULE_SQL);
        }
    }

    @Test
    void startsWithDefaultSchedulersAndAutomaticallyImportsConfiguredSources() throws Exception {
        byte[] zip = GtfsFixtures.valid("25:30:00");
        String hash = GtfsFixtures.hash(zip);
        try (var provider = new FixtureProvider(zip);
                var application = application(provider, true); var client = new LiveClient(application)) {
            await().atMost(Duration.ofSeconds(20)).ignoreException(StatusRuntimeException.class).untilAsserted(() -> {
                for (String source : SOURCES) {
                    assertThat(client.stub().getCurrentHash(currentRequest(source)).getHash()).isEqualTo(hash);
                    assertThat(client.stub().getTripContext(tripRequest(hash, source)).getTripId())
                            .isEqualTo("krakow:" + source + ":001");
                }
            });
            assertThatThrownBy(() -> client.stub().getTripContext(GetTripContextRequest.newBuilder()
                    .setHash(hash).setTripId("krakow:mpk:bad\0id").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            assertThatThrownBy(() -> client.stub().getShape(GetShapeRequest.newBuilder()
                    .setHash(hash).setShapeId("krakow:mpk:bad\0id").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
            assertShape(client, hash);
        }
        assertDownloadsRemoved();
    }

    @Test
    void importsOverHttpAndServesAuthenticatedGrpcAfterRestart() throws Exception {
        byte[] zip = GtfsFixtures.valid("25:30:00");
        String hash = GtfsFixtures.hash(zip);
        try (var provider = new FixtureProvider(zip)) {
            try (var application = application(provider); var client = new LiveClient(application)) {
                assertThatThrownBy(() -> client.anonymous().getCurrentHash(currentRequest("mpk")))
                        .isInstanceOfSatisfying(StatusRuntimeException.class,
                                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

                var result = application.getBean(ImportCycleRunner.class).runCycle();
                assertThat(result.outcomes()).hasSize(3).allSatisfy(outcome -> {
                    assertThat(outcome.failure()).isNull();
                    assertThat(outcome.publication()).isEqualTo(PublicationOutcome.PUBLISHED);
                });
                for (String source : SOURCES) {
                    assertThat(client.stub().getCurrentHash(currentRequest(source)).getHash()).isEqualTo(hash);
                    assertThat(client.stub().getTripContext(tripRequest(hash, source)).getTripId())
                            .isEqualTo("krakow:" + source + ":001");
                }
                GetTripContextResponse trip = client.stub().getTripContext(tripRequest(hash, "mpk"));
                assertThat(trip.getRouteId()).isEqualTo("krakow:mpk:R01");
                assertThat(trip.getStops(0).getStopName()).isEqualTo("Zażółć, gęślą");
                assertThat(trip.getStopsList()).extracting(stop -> stop.getStopSequence()).containsExactly(1, 3, 8);
                assertThat(trip.getStops(1).getArrivalSeconds()).isEqualTo(91800);
                assertShape(client, hash);
            }
            try (var application = application(provider); var client = new LiveClient(application)) {
                assertThat(client.stub().getCurrentHash(currentRequest("mpk")).getHash()).isEqualTo(hash);
                assertThat(client.stub().getTripContext(tripRequest(hash, "mpk")).getStops(1).getArrivalSeconds())
                        .isEqualTo(91800);
                assertThat(application.getBean(ImportCycleRunner.class).runCycle().outcomes())
                        .hasSize(3).allSatisfy(outcome ->
                                assertThat(outcome.publication()).isEqualTo(PublicationOutcome.UNCHANGED));
            }
        }
        assertThat(Files.readAllBytes(temporary.resolve("archives").resolve(hash + ".zip"))).isEqualTo(zip);
        assertDownloadsRemoved();
    }

    @Test
    void rejectedSourceUpdateKeepsPublishedDataWhileOtherSourcesAdvance() throws Exception {
        byte[] first = GtfsFixtures.valid("25:30:00");
        byte[] changed = GtfsFixtures.valid("25:29:00");
        byte[] invalid = GtfsFixtures.invalidReference();
        String oldHash = GtfsFixtures.hash(first);
        try (var provider = new FixtureProvider(first);
                var application = application(provider); var client = new LiveClient(application)) {
            var runner = application.getBean(ImportCycleRunner.class);
            assertThat(runner.runCycle().allOk()).isTrue();
            provider.set("mpk", invalid);
            provider.set("mobilis", changed);

            var result = runner.runCycle();

            assertThat(result.allOk()).isFalse();
            assertThat(result.outcomes().get(0).failure().kind()).isEqualTo(ImportFailure.Kind.VALIDATION);
            assertThat(result.outcomes().get(1).publication()).isEqualTo(PublicationOutcome.PUBLISHED);
            assertThat(result.outcomes().get(2).publication()).isEqualTo(PublicationOutcome.UNCHANGED);
            assertThat(client.stub().getCurrentHash(currentRequest("mpk")).getHash()).isEqualTo(oldHash);
            assertThat(client.stub().getTripContext(tripRequest(oldHash, "mpk")).getStops(1).getArrivalSeconds())
                    .isEqualTo(91800);
            assertThat(client.stub().getCurrentHash(currentRequest("mobilis")).getHash()).isEqualTo(GtfsFixtures.hash(changed));
            assertThat(client.stub().getCurrentHash(currentRequest("tram")).getHash()).isEqualTo(oldHash);
            assertThat(temporary.resolve("archives").resolve(GtfsFixtures.hash(invalid) + ".zip")).isRegularFile();
        }
        assertDownloadsRemoved();
    }

    @Test
    void preservesAbsentFieldsThroughCsvStorageAndGrpcWithoutLosingShapePoints() throws Exception {
        var tables = GtfsFixtures.tables("25:30:00");
        tables.put("trips.txt", "trip_id,route_id,service_id,direction_id,trip_headsign,shape_id\n001,R01,weekday\n");
        tables.put("stops.txt", tables.get("stops.txt").replace("50.1,19.9", ","));
        byte[] zip = GtfsFixtures.zip(tables);
        String hash = GtfsFixtures.hash(zip);
        try (var provider = new FixtureProvider(zip);
                var application = application(provider); var client = new LiveClient(application)) {
            assertThat(application.getBean(ImportCycleRunner.class).runCycle().allOk()).isTrue();

            GetTripContextResponse trip = client.stub().getTripContext(tripRequest(hash, "mpk"));

            assertThat(trip.hasDirectionId()).isFalse();
            assertThat(trip.hasHeadsign()).isFalse();
            assertThat(trip.hasShapeId()).isFalse();
            assertThat(trip.getStopsCount()).isEqualTo(3);
            assertThat(trip.getStops(0).hasLatitude()).isFalse();
            assertThat(trip.getStops(0).hasLongitude()).isFalse();
            assertShape(client, hash);
        }
        assertDownloadsRemoved();
    }

    private void assertShape(LiveClient client, String hash) {
        var shape = client.stub().getShape(GetShapeRequest.newBuilder().setHash(hash)
                .setShapeId("krakow:mpk:0007").build());
        assertThat(shape.getPointsList()).extracting(ShapePoint::getSequence).containsExactly(1, 1, 3);
        assertThat(shape.getPoints(0).getLatitude()).isEqualTo(50.1);
    }

    private void assertDownloadsRemoved() throws IOException {
        try (var files = Files.walk(temporary.resolve("downloads"))) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".zip"))).isEmpty();
        }
    }

    private ConfigurableApplicationContext application(FixtureProvider provider) {
        return application(provider, false);
    }

    private ConfigurableApplicationContext application(FixtureProvider provider, boolean jobsEnabled) {
        var arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + RUNTIME_USER,
                "--spring.datasource.password=" + RUNTIME_PASSWORD,
                "--spring.grpc.server.address=127.0.0.1", "--spring.grpc.server.port=0",
                "--schedule.jobs-enabled=" + jobsEnabled, "--schedule.grpc-token=" + TOKEN,
                "--schedule.download.attempts=1",
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

    private static GetCurrentHashRequest currentRequest(String source) {
        return GetCurrentHashRequest.newBuilder().setFeedId(FeedId.newBuilder()
                .setCityId("krakow").setSourceId(source)).build();
    }

    private static GetTripContextRequest tripRequest(String hash, String source) {
        return GetTripContextRequest.newBuilder().setHash(hash).setTripId("krakow:" + source + ":001").build();
    }

    private static final class LiveClient implements AutoCloseable {
        private final ManagedChannel channel;
        private final ScheduleServiceGrpc.ScheduleServiceBlockingStub stub;

        LiveClient(ConfigurableApplicationContext application) {
            int port = application.getBean(GrpcServerLifecycle.class).getPort();
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            var headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TOKEN);
            stub = ScheduleServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }

        ScheduleServiceGrpc.ScheduleServiceBlockingStub stub() { return stub.withDeadlineAfter(5, TimeUnit.SECONDS); }

        ScheduleServiceGrpc.ScheduleServiceBlockingStub anonymous() {
            return ScheduleServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }

    private static final class FixtureProvider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final ConcurrentHashMap<String, byte[]> content = new ConcurrentHashMap<>();

        FixtureProvider(byte[] first) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (String source : SOURCES) {
                content.put(source, first);
                server.createContext("/" + source, exchange -> {
                    try (exchange) {
                        byte[] response = content.get(source);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                });
            }
            server.setExecutor(executor);
            server.start();
        }

        String url(String source) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + source; }

        void set(String source, byte[] zip) { content.put(source, zip); }

        @Override
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
