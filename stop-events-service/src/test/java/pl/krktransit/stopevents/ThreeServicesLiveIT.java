package pl.krktransit.stopevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pl.krktransit.stopevents.support.DetectorFixtures.SOURCE;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.Network;
import pl.krktransit.stopevents.adapter.outbound.schedule.GrpcScheduleContexts;
import pl.krktransit.stopevents.application.port.ScheduleFailure;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.support.*;

@EnabledIfSystemProperty(named = "stop-events.live", matches = "true")
class ThreeServicesLiveIT {
    private static final String CREATE_ROLES = """
            CREATE ROLE schedule_owner LOGIN PASSWORD 'disposable-owner';
            CREATE ROLE stop_owner LOGIN PASSWORD 'disposable-owner';
            CREATE ROLE schedule_runtime LOGIN PASSWORD 'disposable-runtime';
            CREATE ROLE stop_runtime LOGIN PASSWORD 'disposable-runtime';
            GRANT CREATE ON DATABASE stop_events_test TO schedule_owner, stop_owner
            """;
    private static final String RUNTIME_GRANTS = """
            GRANT USAGE ON SCHEMA gtfs_schedule TO schedule_runtime;
            GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA gtfs_schedule TO schedule_runtime;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA gtfs_schedule TO schedule_runtime;
            GRANT USAGE ON SCHEMA stop_events TO stop_runtime;
            GRANT INSERT, SELECT ON stop_events.events TO stop_runtime;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA stop_events TO stop_runtime
            """;
    private static final String COUNTS = """
            SELECT source_id, count(*) AS events, count(*) FILTER (WHERE is_estimated) AS estimated,
                   count(DISTINCT trip_id) AS trips
            FROM stop_events.events GROUP BY source_id ORDER BY source_id
            """;
    private static final String INVALID = """
            SELECT count(*) FROM stop_events.events
            WHERE direction_id IS NULL OR headsign !~ '[^[:space:]]' OR license_plate !~ '[^[:space:]]'
               OR delay_seconds <> trunc(extract(epoch FROM event_time - planned_time))::integer
            """;
    private static final String SAMPLE = """
            SELECT source_id, line_number, stop_sequence, detection_method, delay_seconds, static_hash
            FROM stop_events.events ORDER BY id LIMIT 10
            """;
    private static final String EXPORT = """
            SELECT source_id, trip_id, service_date, stop_sequence, stop_id, line_number, planned_time, event_time,
                   delay_seconds, detection_method, is_estimated, license_plate, static_hash, created_at
            FROM stop_events.events ORDER BY id
            """;
    private static final Duration HALF_OBSERVATION =
            Duration.ofSeconds(Long.getLong("stop-events.live.seconds", 120) / 2);
    private static final String STREAM_PREFIX = "krktransit:realtime:observations:v1:";
    private static final List<SourceId> SOURCES = List.of(SOURCE,
            new SourceId("krakow", "mpk-tram"), new SourceId("krakow", "mobilis-bus"));
    private static final Path ARTIFACTS = Path.of("target", "live-stack");

    @Test
    void threePackagedServicesProcessRealFeedsInAnOwnedStack() throws IOException, SQLException {
        String owner = UUID.randomUUID().toString();
        try (var network = Network.builder().createNetworkCmdModifier(command ->
                    command.withLabels(Map.of("krktransit.test-owner", owner))).build();
             var database = new OwnedPostgres(); var redis = new OwnedRedis()) {
            database.withNetwork(network).withNetworkAliases("postgres");
            redis.withNetwork(network).withNetworkAliases("redis");
            database.start();
            redis.start();
            configureRoles(database.dataSource());
            configureRedis(redis);
            migrate(network, owner);
            grantRuntime(database.dataSource());
            runServices(network, owner, database, redis);
        }
    }

    private void migrate(Network network, String owner) {
        try (var schedule = new LiveServiceContainer("schedule-service", network, owner, scheduleDatabase("schedule_owner", "disposable-owner")).migration();
             var stops = new LiveServiceContainer("stop-events-service", network, owner, stopDatabase("stop_owner", "disposable-owner")).migration()) {
            schedule.start();
            stops.start();
        }
    }

    private void runServices(Network network, String owner, OwnedPostgres database, OwnedRedis redis) throws IOException, SQLException {
        try (var schedule = new LiveServiceContainer("schedule-service", network, owner, scheduleEnvironment()).grpcPort()
                        .readyLog(".*Started ScheduleApplication.*\n");
             var ingestion = new LiveServiceContainer("realtime-ingestion-service", network, owner, ingestionEnvironment())
                        .readyLog(".*Realtime ingestion started.*\n");
             var stops = new LiveServiceContainer("stop-events-service", network, owner, stopEnvironment())
                        .readyLog(".*Stop events detector started.*\n")) {
            try {
                schedule.start();
                waitForSchedules(schedule);
                stops.start();
                ingestion.start();
                var startedAt = Instant.now();
                await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2))
                        .until(() -> sourceCounts(database.dataSource()).size() == 3);
                observeHalf();
                var restartedAt = restartDetector(stops, database.dataSource(), redis);
                observeHalf();
                assertThat(schedule.isRunning()).isTrue();
                assertThat(ingestion.isRunning()).isTrue();
                assertThat(stops.isRunning()).isTrue();
                verifyAndReport(database.dataSource(), redis, new RunTiming(startedAt, restartedAt));
            } finally {
                Files.createDirectories(ARTIFACTS);
                saveLog("schedule", schedule);
                saveLog("ingestion", ingestion);
                saveLog("stop-events", stops);
            }
        }
    }

    private void observeHalf() {
        await().pollDelay(HALF_OBSERVATION).atMost(HALF_OBSERVATION.plusSeconds(15)).until(() -> true);
    }

    private Instant restartDetector(LiveServiceContainer stops, DataSource source, OwnedRedis redis) throws IOException, SQLException {
        var before = sourceCounts(source);
        var cursorsBefore = cursors(redis);
        Files.createDirectories(ARTIFACTS);
        saveLog("stop-events-before-restart", stops);
        stops.stop();
        var restartedAt = Instant.now();
        stops.start();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            var after = sourceCounts(source);
            return before.entrySet().stream().allMatch(entry -> after.getOrDefault(entry.getKey(), 0L) > entry.getValue());
        });
        var cursorsAfter = cursors(redis);
        SOURCES.forEach(id -> assertThat(compareIds(cursorsAfter.get(id), cursorsBefore.get(id))).isPositive());
        return restartedAt;
    }

    private int compareIds(String first, String second) {
        var left = first.split("-");
        var right = second.split("-");
        int millis = Long.compareUnsigned(Long.parseUnsignedLong(left[0]), Long.parseUnsignedLong(right[0]));
        if (millis != 0) return millis;
        return Long.compareUnsigned(Long.parseUnsignedLong(left[1]), Long.parseUnsignedLong(right[1]));
    }

    private void waitForSchedules(LiveServiceContainer container) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(container.getHost(), container.getMappedPort(9090))
                .usePlaintext().build();
        try {
            var schedule = new GrpcScheduleContexts(channel, "disposable-grpc-token", Duration.ofSeconds(2));
            await().atMost(Duration.ofMinutes(6)).pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptionsMatching(failure -> failure instanceof ScheduleFailure)
                    .until(() -> SOURCES.stream().allMatch(source -> !schedule.currentHash(source).isBlank()));
        } finally { channel.shutdownNow(); }
    }

    private Map<String, Long> sourceCounts(DataSource source) throws SQLException {
        var counts = new LinkedHashMap<String, Long>();
        try (var connection = source.getConnection(); var query = connection.prepareStatement(COUNTS);
             var rows = query.executeQuery()) {
            while (rows.next()) counts.put(rows.getString("source_id"), rows.getLong("events"));
        }
        return counts;
    }

    private void verifyAndReport(DataSource source, OwnedRedis redis, RunTiming timing) throws IOException {
        var finishedAt = Instant.now();
        var report = new StringBuilder("Live stack completed at " + finishedAt + "\n");
        report.append("Observation started: ").append(timing.startedAt()).append('\n');
        report.append("Detector restarted: ").append(timing.restartedAt()).append('\n');
        report.append("Observation duration: ").append(Duration.between(timing.startedAt(), finishedAt).toSeconds())
                .append(" seconds\n");
        try (var connection = source.getConnection(); var counts = connection.prepareStatement(COUNTS);
             var invalid = connection.prepareStatement(INVALID); var sample = connection.prepareStatement(SAMPLE)) {
            try (var rows = invalid.executeQuery()) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
            try (var rows = counts.executeQuery()) {
                while (rows.next()) report.append(rows.getString("source_id")).append(": events=")
                        .append(rows.getLong("events")).append(", estimated=").append(rows.getLong("estimated"))
                        .append(", trips=").append(rows.getLong("trips")).append('\n');
            }
            report.append("Samples:\n");
            try (var rows = sample.executeQuery()) {
                while (rows.next()) report.append(rows.getString(1)).append(' ').append(rows.getString(2)).append(' ')
                        .append(rows.getInt(3)).append(' ').append(rows.getInt(4)).append(' ')
                        .append(rows.getInt(5)).append(' ').append(rows.getString(6)).append('\n');
            }
        } catch (SQLException failure) { throw new AssertionError(failure); }
        appendStreams(report, redis);
        Files.createDirectories(ARTIFACTS);
        exportEvents(source);
        Files.writeString(ARTIFACTS.resolve("summary.txt"), report);
        System.out.println(report);
    }

    private void appendStreams(StringBuilder report, OwnedRedis redis) {
        var cursors = cursors(redis);
        var client = RedisClient.create(redis.ownedUrl());
        try (var connection = client.connect()) {
            for (var source : SOURCES) {
                var info = connection.sync().xinfoStream(STREAM_PREFIX + source);
                var fields = new HashMap<String, Object>();
                for (int i = 0; i < info.size(); i += 2) fields.put(String.valueOf(info.get(i)), info.get(i + 1));
                String last = String.valueOf(fields.get("last-generated-id"));
                report.append(source).append(" stream length=").append(fields.get("length"))
                        .append(", last=").append(last).append(", cursor=").append(cursors.get(source))
                        .append(", cursor lag ms=").append(millis(last) - millis(cursors.get(source))).append('\n');
            }
        } finally { client.shutdown(); }
    }

    private long millis(String id) { return Long.parseLong(id.substring(0, id.indexOf('-'))); }

    private void exportEvents(DataSource source) throws IOException {
        var csv = new StringBuilder();
        try (var connection = source.getConnection(); var query = connection.prepareStatement(EXPORT);
             var rows = query.executeQuery()) {
            int columns = rows.getMetaData().getColumnCount();
            for (int i = 1; i <= columns; i++) csv.append(i > 1 ? "\t" : "").append(rows.getMetaData().getColumnName(i));
            csv.append('\n');
            while (rows.next()) {
                for (int i = 1; i <= columns; i++) csv.append(i > 1 ? "\t" : "").append(rows.getString(i));
                csv.append('\n');
            }
        } catch (SQLException failure) { throw new AssertionError(failure); }
        Files.writeString(ARTIFACTS.resolve("events.tsv"), csv);
    }

    private Map<SourceId, String> cursors(OwnedRedis redis) {
        var client = RedisClient.create(redis.ownedUrl());
        var cursors = new LinkedHashMap<SourceId, String>();
        try (var connection = client.connect()) {
            for (var source : SOURCES) {
                var cursor = connection.sync().get("krktransit:stop-events:v1:cursor:" + source);
                assertThat(cursor).isNotBlank();
                cursors.put(source, cursor);
            }
        } finally { client.shutdown(); }
        return cursors;
    }

    private void saveLog(String name, LiveServiceContainer service) throws IOException {
        if (service.getContainerId() != null) Files.writeString(ARTIFACTS.resolve(name + ".log"), service.getLogs());
    }

    private void configureRoles(DataSource source) { execute(source, CREATE_ROLES); }
    private void grantRuntime(DataSource source) { execute(source, RUNTIME_GRANTS); }

    private void execute(DataSource source, String sql) {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }

    private void configureRedis(OwnedRedis redis) {
        var client = RedisClient.create(redis.ownedUrl());
        try (var connection = client.connect()) {
            acl(connection.sync(), "ingestion", "on", ">disposable-ingestion", "~krktransit:realtime:observations:v1:*",
                    "+ping", "+xadd", "+hello", "+client|setname", "+client|setinfo");
            acl(connection.sync(), "stop-events", "on", ">disposable-stops", "~krktransit:stop-events:*",
                    "%R~krktransit:realtime:observations:v1:*", "+ping", "+hello", "+client|setname", "+client|setinfo",
                    "+xread", "+xinfo|stream", "+get", "+set", "+hgetall", "+hset", "+del", "+expire", "+multi", "+exec");
        } finally { client.shutdown(); }
    }

    private void acl(io.lettuce.core.api.sync.RedisCommands<String, String> commands, String user, String... rules) {
        var codec = io.lettuce.core.codec.StringCodec.UTF8;
        var arguments = new io.lettuce.core.protocol.CommandArgs<>(codec).add("SETUSER").add(user);
        for (var rule : rules) arguments.add(rule);
        commands.dispatch(io.lettuce.core.protocol.CommandType.ACL,
                new io.lettuce.core.output.StatusOutput<>(codec), arguments);
    }

    private Map<String, String> scheduleEnvironment() {
        var environment = new HashMap<>(scheduleDatabase("schedule_runtime", "disposable-runtime"));
        environment.put("SCHEDULE_GRPC_ADDRESS", "0.0.0.0");
        environment.put("SCHEDULE_GRPC_TOKEN", "disposable-grpc-token");
        return environment;
    }

    private Map<String, String> stopEnvironment() {
        var environment = new HashMap<>(stopDatabase("stop_runtime", "disposable-runtime"));
        environment.put("STOP_EVENTS_REDIS_URL", "redis://redis:6379");
        environment.put("STOP_EVENTS_REDIS_USERNAME", "stop-events");
        environment.put("STOP_EVENTS_REDIS_PASSWORD", "disposable-stops");
        environment.put("STOP_EVENTS_SCHEDULE_ADDRESS", "static://schedule-service:9090");
        environment.put("STOP_EVENTS_SCHEDULE_TOKEN", "disposable-grpc-token");
        return environment;
    }

    private Map<String, String> ingestionEnvironment() {
        return Map.of("REALTIME_REDIS_URL", "redis://redis:6379", "REALTIME_REDIS_USERNAME", "ingestion",
                "REALTIME_REDIS_PASSWORD", "disposable-ingestion");
    }

    private Map<String, String> scheduleDatabase(String user, String password) {
        return Map.of("SCHEDULE_DB_URL", "jdbc:postgresql://postgres:5432/stop_events_test",
                "SCHEDULE_DB_USER", user, "SCHEDULE_DB_PASSWORD", password);
    }

    private Map<String, String> stopDatabase(String user, String password) {
        return Map.of("STOP_EVENTS_DB_URL", "jdbc:postgresql://postgres:5432/stop_events_test",
                "STOP_EVENTS_DB_USER", user, "STOP_EVENTS_DB_PASSWORD", password);
    }

    private record RunTiming(Instant startedAt, Instant restartedAt) {}
}
