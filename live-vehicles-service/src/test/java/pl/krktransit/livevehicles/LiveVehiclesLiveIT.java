package pl.krktransit.livevehicles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.contracts.v1.VehiclePosition;
import pl.krktransit.livevehicles.support.OwnedRedis;
import pl.krktransit.schedule.contracts.v1.FeedId;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;

@EnabledIfSystemProperty(named = "live-vehicles.live", matches = "true")
class LiveVehiclesLiveIT {
    private static final String TOKEN = "disposable-grpc-token";
    private static final Path ARTIFACTS = Path.of("target", "live-vehicles-live");
    private static final List<String> SOURCES = List.of("mpk-bus", "mobilis-bus", "mpk-tram");
    private static final String CREATE_ROLES = """
            CREATE ROLE schedule_owner LOGIN PASSWORD 'disposable-owner';
            CREATE ROLE schedule_runtime LOGIN PASSWORD 'disposable-runtime';
            GRANT CREATE ON DATABASE live_vehicles_test TO schedule_owner
            """;
    private static final String RUNTIME_GRANTS = """
            GRANT USAGE ON SCHEMA gtfs_schedule TO schedule_runtime;
            GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA gtfs_schedule TO schedule_runtime;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA gtfs_schedule TO schedule_runtime
            """;

    @Test
    void packagedServicesExposeRealVehiclePositionsResolvableInScheduleAndRebuildTheMapAfterRestart() throws IOException, SQLException {
        String owner = UUID.randomUUID().toString();
        try (var network = Network.builder().createNetworkCmdModifier(command -> command.withLabels(Map.of("krktransit.test-owner", owner))).build();
             var database = new PostgreSQLContainer("postgres:17").withDatabaseName("live_vehicles_test")
                     .withUsername("test_owner").withPassword("disposable-only");
             var redis = new OwnedRedis()) {
            database.withReuse(false).withLabel("krktransit.test-owner", owner).withNetwork(network).withNetworkAliases("postgres");
            database.withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(1024L * 1024 * 1024)
                    .withNanoCPUs(2_000_000_000L).withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
            redis.withNetwork(network).withNetworkAliases("redis");
            database.start();
            redis.start();
            sql(database, CREATE_ROLES);
            redisAccounts(redis);
            try (var migration = new Service("schedule-service", network, owner, databaseSettings("schedule_owner", "disposable-owner"))) {
                migration.withCommand("java", "-Xmx256m", "-jar", "/app/service.jar", "--migrate");
                migration.withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
                migration.waitingFor(Wait.forLogMessage(".*Successfully applied.*\n", 1));
                migration.start();
            }
            sql(database, RUNTIME_GRANTS);
            runServices(network, owner);
        }
    }

    private void runServices(Network network, String owner) throws IOException {
        var settings = new HashMap<>(databaseSettings("schedule_runtime", "disposable-runtime"));
        settings.put("SCHEDULE_GRPC_ADDRESS", "0.0.0.0");
        settings.put("SCHEDULE_GRPC_TOKEN", TOKEN);
        try (var schedule = new Service("schedule-service", network, owner, settings).grpc(9090).ready(".*Started ScheduleApplication.*\n");
             var ingestion = new Service("realtime-ingestion-service", network, owner, Map.of("REALTIME_REDIS_URL", "redis://redis:6379",
                     "REALTIME_REDIS_USERNAME", "ingestion", "REALTIME_REDIS_PASSWORD", "disposable-ingestion"))
                     .ready(".*Realtime ingestion started.*\n");
             var live = new Service("live-vehicles-service", network, owner, liveSettings()).grpc(9091).ready(".*Live vehicles projector started.*\n")) {
            try {
                schedule.start();
                awaitSchedules(schedule);
                live.start();
                ingestion.start();
                verifyLiveFlow(schedule, live);
                assertThat(schedule.isRunning()).isTrue();
                assertThat(ingestion.isRunning()).isTrue();
            } finally {
                Files.createDirectories(ARTIFACTS);
                if (schedule.getContainerId() != null) Files.writeString(ARTIFACTS.resolve("schedule.log"), schedule.getLogs());
                if (ingestion.getContainerId() != null) Files.writeString(ARTIFACTS.resolve("ingestion.log"), ingestion.getLogs());
                if (live.getContainerId() != null) Files.writeString(ARTIFACTS.resolve("live-vehicles.log"), live.getLogs());
            }
        }
    }

    private void verifyLiveFlow(Service schedule, Service live) throws IOException {
        Files.createDirectories(ARTIFACTS);
        ListVehiclePositionsResponse first;
        ListVehiclePositionsResponse updated;
        var started = Instant.now();
        var channel = channel(live, 9091);
        try {
            await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).until(() -> positions(channel).getVehiclesCount() > 0);
            first = positions(channel);
            validate(first);
            Files.writeString(ARTIFACTS.resolve("positions-before.tsv"), export(first));
            var before = byVehicle(first);
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> advances(before, positions(channel)));
            updated = positions(channel);
            validate(updated);
            verifyScheduleReferences(schedule, updated);
        } finally { channel.shutdownNow(); }

        Files.writeString(ARTIFACTS.resolve("live-vehicles-before-restart.log"), live.getLogs());
        live.stop();
        var restarted = Instant.now();
        live.start();
        var afterChannel = channel(live, 9091);
        try {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> positions(afterChannel).getVehiclesCount() > 0);
            var rebuilt = positions(afterChannel);
            var beforeRestart = byVehicle(updated);
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> advances(beforeRestart, positions(afterChannel)));
            var after = positions(afterChannel);
            validate(after);
            Files.writeString(ARTIFACTS.resolve("positions-after.tsv"), export(after));
            Files.writeString(ARTIFACTS.resolve("summary.txt"), "Observation started: " + started + "\nRestarted: " + restarted
                    + "\nFinished: " + Instant.now() + "\nFirst snapshot: " + counts(first) + "\nUpdated snapshot: " + counts(updated)
                    + "\nRebuilt snapshot: " + counts(rebuilt) + "\nFinal snapshot: " + counts(after)
                    + "\nChanged coordinates: " + moved(byVehicle(first), after) + "\n");
        } finally { afterChannel.shutdownNow(); }
    }

    private void validate(ListVehiclePositionsResponse response) {
        assertThat(response.getVehiclesCount()).isBetween(1, 10000);
        var now = Instant.now();
        assertThat(response.getVehiclesList()).allSatisfy(position -> {
            assertThat(position.getLatitude()).isBetween(-90.0, 90.0);
            assertThat(position.getLongitude()).isBetween(-180.0, 180.0);
            if (position.hasTripId()) assertThat(position.getTripId()).startsWith(position.getCityId() + ":" + position.getSourceId() + ":");
            assertThat(position.hasLicensePlate() || position.hasVehicleId()).isTrue();
            assertThat(Duration.between(Instant.ofEpochSecond(position.getFetchedAt().getSeconds(), position.getFetchedAt().getNanos()), now))
                    .isLessThan(Duration.ofMinutes(10));
        });
    }

    private void verifyScheduleReferences(Service schedule, ListVehiclePositionsResponse positions) throws IOException {
        var channel = channel(schedule, 9090);
        var report = new StringBuilder();
        try {
            var stub = ScheduleServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers()));
            var samples = positions.getVehiclesList().stream().filter(VehiclePosition::hasTripId)
                    .collect(Collectors.toMap(VehiclePosition::getSourceId, value -> value, (a, b) -> a));
            for (var position : samples.values()) {
                String hash = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getCurrentHash(GetCurrentHashRequest.newBuilder()
                        .setFeedId(FeedId.newBuilder().setCityId(position.getCityId()).setSourceId(position.getSourceId())).build()).getHash();
                var trip = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getTripContext(GetTripContextRequest.newBuilder()
                        .setHash(hash).setTripId(position.getTripId()).build());
                assertThat(trip.getLineNumber()).isNotBlank();
                assertThat(trip.getStopsCount()).isPositive();
                report.append(position.getSourceId()).append(": line=").append(trip.getLineNumber()).append(", stops=").append(trip.getStopsCount());
                if (trip.hasShapeId()) {
                    var shape = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getShape(GetShapeRequest.newBuilder()
                            .setHash(hash).setShapeId(trip.getShapeId()).build());
                    assertThat(shape.getPointsCount()).isPositive();
                    report.append(", shape_points=").append(shape.getPointsCount());
                }
                report.append('\n');
            }
            Files.writeString(ARTIFACTS.resolve("schedule-references.txt"), report);
        } finally { channel.shutdownNow(); }
    }

    private void awaitSchedules(Service schedule) {
        var channel = channel(schedule, 9090);
        try {
            var stub = ScheduleServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers()));
            await().atMost(Duration.ofMinutes(6)).pollInterval(Duration.ofSeconds(2)).ignoreExceptionsMatching(error -> error instanceof StatusRuntimeException)
                    .until(() -> SOURCES.stream().allMatch(source -> !stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                            .getCurrentHash(GetCurrentHashRequest.newBuilder().setFeedId(FeedId.newBuilder()
                                    .setCityId("krakow").setSourceId(source)).build()).getHash().isBlank()));
        } finally { channel.shutdownNow(); }
    }

    private ListVehiclePositionsResponse positions(ManagedChannel channel) {
        return LiveVehiclesServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers()))
                .withDeadlineAfter(2, TimeUnit.SECONDS).listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance());
    }

    private ManagedChannel channel(Service service, int port) {
        return ManagedChannelBuilder.forAddress(service.getHost(), service.getMappedPort(port)).usePlaintext().build();
    }

    private Metadata headers() {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TOKEN);
        return headers;
    }

    private Map<String, VehiclePosition> byVehicle(ListVehiclePositionsResponse response) {
        return response.getVehiclesList().stream().collect(Collectors.toMap(position -> position.getSourceId() + ":"
                + (position.hasLicensePlate() ? "plate:" + position.getLicensePlate() : "id:" + position.getVehicleId()), value -> value));
    }

    private boolean advances(Map<String, VehiclePosition> before, ListVehiclePositionsResponse response) {
        return byVehicle(response).entrySet().stream().anyMatch(entry -> before.containsKey(entry.getKey())
                && entry.getValue().getTimestamp().getSeconds() > before.get(entry.getKey()).getTimestamp().getSeconds());
    }

    private long moved(Map<String, VehiclePosition> before, ListVehiclePositionsResponse response) {
        return byVehicle(response).entrySet().stream().filter(entry -> before.containsKey(entry.getKey())
                && (entry.getValue().getLatitude() != before.get(entry.getKey()).getLatitude()
                    || entry.getValue().getLongitude() != before.get(entry.getKey()).getLongitude())).count();
    }

    private Map<String, Long> counts(ListVehiclePositionsResponse response) {
        return response.getVehiclesList().stream().collect(Collectors.groupingBy(VehiclePosition::getSourceId, LinkedHashMap::new, Collectors.counting()));
    }

    private String export(ListVehiclePositionsResponse response) {
        var text = new StringBuilder("source\tplate\tvehicle\ttrip\tlatitude\tlongitude\ttimestamp\tfetched_at\n");
        response.getVehiclesList().forEach(position -> text.append(position.getSourceId()).append('\t').append(position.getLicensePlate())
                .append('\t').append(position.getVehicleId()).append('\t').append(position.getTripId())
                .append('\t').append(position.getLatitude()).append('\t').append(position.getLongitude())
                .append('\t').append(position.getTimestamp().getSeconds()).append('\t').append(position.getFetchedAt().getSeconds()).append('\n'));
        return text.toString();
    }

    private void sql(PostgreSQLContainer database, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private Map<String, String> databaseSettings(String user, String password) {
        return Map.of("SCHEDULE_DB_URL", "jdbc:postgresql://postgres:5432/live_vehicles_test", "SCHEDULE_DB_USER", user, "SCHEDULE_DB_PASSWORD", password);
    }

    private Map<String, String> liveSettings() {
        return Map.of("LIVE_VEHICLES_REDIS_URL", "redis://redis:6379", "LIVE_VEHICLES_REDIS_USERNAME", "live-vehicles",
                "LIVE_VEHICLES_REDIS_PASSWORD", "disposable-live", "LIVE_VEHICLES_GRPC_ADDRESS", "0.0.0.0", "LIVE_VEHICLES_GRPC_TOKEN", TOKEN);
    }

    private void redisAccounts(OwnedRedis redis) {
        var client = RedisClient.create(redis.ownedUrl());
        try (var connection = client.connect()) {
            acl(connection.sync(), "ingestion", "on", ">disposable-ingestion", "resetkeys", "~krktransit:realtime:observations:v1:*",
                    "-@all", "+ping", "+xadd", "+hello", "+client|setname", "+client|setinfo");
            acl(connection.sync(), "live-vehicles", "on", ">disposable-live", "resetkeys", "%R~krktransit:realtime:observations:v1:*",
                    "-@all", "+time", "+xread", "+hello", "+client|setname", "+client|setinfo");
        } finally { client.shutdown(); }
    }

    private void acl(io.lettuce.core.api.sync.RedisCommands<String, String> commands, String user, String... rules) {
        var arguments = new io.lettuce.core.protocol.CommandArgs<>(StringCodec.UTF8).add("SETUSER").add(user);
        for (var rule : rules) arguments.add(rule);
        commands.dispatch(io.lettuce.core.protocol.CommandType.ACL, new io.lettuce.core.output.StatusOutput<>(StringCodec.UTF8), arguments);
    }

    private static final class Service extends GenericContainer<Service> {
        private Service(String module, Network network, String owner, Map<String, String> environment) {
            super("eclipse-temurin:25-jre");
            withReuse(false);
            withLabel("krktransit.test-owner", owner);
            withNetwork(network);
            withNetworkAliases(module);
            withEnv(environment);
            withCopyFileToContainer(MountableFile.forHostPath(artifact(module)), "/app/service.jar");
            withCommand("java", "-Xmx384m", "-jar", "/app/service.jar");
            withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(768L * 1024 * 1024).withNanoCPUs(2_000_000_000L));
        }

        private Service grpc(int port) {
            withExposedPorts(port);
            withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(port))));
            return this;
        }

        private Service ready(String expression) {
            waitingFor(Wait.forLogMessage(expression, 1).withStartupTimeout(Duration.ofMinutes(2)));
            return this;
        }

        private static Path artifact(String module) {
            var root = Path.of("").toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("schedule-service"))) root = root.getParent();
            if (root == null) throw new IllegalStateException("Cannot locate the reactor root");
            var jar = root.resolve(module + "/target/" + module + "-0.1.0.jar");
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Package all services before the live test: " + jar);
            return jar;
        }
    }
}
