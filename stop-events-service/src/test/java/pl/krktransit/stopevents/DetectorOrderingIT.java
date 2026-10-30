package pl.krktransit.stopevents;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import io.lettuce.core.RedisClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcStopEventStore;
import pl.krktransit.stopevents.adapter.outbound.redis.RedisDetectorStateStore;
import pl.krktransit.stopevents.application.processing.ProcessObservationBatch;
import pl.krktransit.stopevents.application.processing.SourceBatch;
import pl.krktransit.stopevents.application.processing.StreamEntry;
import pl.krktransit.stopevents.bootstrap.MigrationCommand;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.support.InMemoryPorts;
import pl.krktransit.stopevents.support.OwnedPostgres;
import pl.krktransit.stopevents.support.OwnedRedis;

@Testcontainers
class DetectorOrderingIT {
    private static final String CLEAR = "DELETE FROM stop_events.events";
    @Container
    private static final OwnedPostgres DATABASE = new OwnedPostgres();
    @Container
    private static final OwnedRedis REDIS = new OwnedRedis();
    private RedisClient client;
    private RedisDetectorStateStore state;
    private JdbcStopEventStore events;
    private ProcessObservationBatch process;
    private String prefix;
    private int cursor;
    private final VehicleKey vehicle = new VehicleKey(SOURCE, "KR123");

    @BeforeAll
    static void migrate() { MigrationCommand.migrate(DATABASE.dataSource()); }

    @BeforeEach
    void prepare() throws SQLException {
        var source = DATABASE.dataSource();
        try (var connection = source.getConnection(); var clear = connection.prepareStatement(CLEAR)) {
            clear.executeUpdate();
        }
        events = new JdbcStopEventStore(source);
        client = RedisClient.create(REDIS.ownedUrl());
        prefix = "krktransit:stop-events:test:" + UUID.randomUUID();
        openProcessor();
    }

    @AfterEach
    void close() { state.close(); client.shutdown(); }

    @Test
    void delayedHistoryAndCurrentStateSurviveProcessorRestartAndReplay() {
        observe(3, 1, 125, 125);
        var current = state.vehicle(vehicle);
        observe(2, 1, 90, 300);
        assertThat(state.vehicle(vehicle)).isEqualTo(current);
        var stored = events.storedStops(Set.of(TRIP), DATE);
        assertThat(stored).containsOnlyKeys(new EventKey(TRIP, DATE, 2), new EventKey(TRIP, DATE, 3));
        assertThat(state.savedStops(new TripKey(SOURCE, TRIP), DATE)).isEqualTo(stored);
        state.close();
        openProcessor();
        observe(2, 1, 90, 301);
        assertThat(state.vehicle(vehicle)).isEqualTo(current);
        assertThat(events.storedStops(Set.of(TRIP), DATE)).isEqualTo(stored);
        assertThat(state.cursors(Set.of(SOURCE))).containsEntry(SOURCE, "3-0");
    }

    @Test
    void aLateContradictoryPredecessorCannotChangeSqlOrRedisHistory() {
        observe(3, 1, 125, 125);
        observe(3, 2, 150, 150);
        var current = state.vehicle(vehicle);
        var stored = events.storedStops(Set.of(TRIP), DATE);
        observe(2, 1, 140, 300);
        assertThat(events.storedStops(Set.of(TRIP), DATE)).isEqualTo(stored);
        assertThat(state.savedStops(new TripKey(SOURCE, TRIP), DATE)).isEqualTo(stored);
        assertThat(state.vehicle(vehicle)).isEqualTo(current);
    }

    @Test
    void sqlRecoveryProvidesTheSuccessorEvenWhenRedisHasNoSavedStops() {
        var successor = event(3, 5, BASE.plusSeconds(125));
        events.insert(List.of(successor));
        assertThat(state.savedStops(new TripKey(SOURCE, TRIP), DATE)).isEmpty();
        observe(2, 1, 140, 300);
        assertThat(events.storedStops(Set.of(TRIP), DATE)).containsOnlyKeys(successor.key());
        assertThat(state.savedStops(new TripKey(SOURCE, TRIP), DATE)).containsEntry(successor.key(), successor.savedStop());
        assertThat(state.cursors(Set.of(SOURCE))).containsEntry(SOURCE, "1-0");
    }

    private void openProcessor() {
        state = new RedisDetectorStateStore(() -> REDIS.connect(client), prefix, Duration.ofHours(3));
        process = new ProcessObservationBatch(state, events, new InMemoryPorts.Schedule(), Duration.ofHours(3));
    }

    private void observe(int sequence, int status, int timestamp, int fetched) {
        var observation = new VehicleObservation(SOURCE, BASE.plusSeconds(fetched), TRIP, "KR123", "vehicle",
                sequence, status, BASE.plusSeconds(timestamp));
        process.process(List.of(new SourceBatch(SOURCE, List.of(new StreamEntry(++cursor + "-0", observation)))));
    }
}
