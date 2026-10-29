package pl.krktransit.stopevents.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import pl.krktransit.stopevents.application.port.StoredStopConflict;
import pl.krktransit.stopevents.bootstrap.MigrationCommand;
import pl.krktransit.stopevents.domain.event.*;
import pl.krktransit.stopevents.support.OwnedPostgres;

@Testcontainers
class JdbcStopEventStoreIT {
    private static final String CLEAR = "DELETE FROM stop_events.events";
    private static final String COUNT = "SELECT count(*) FROM stop_events.events";
    private static final String PARTITION = "SELECT tableoid::regclass::text FROM stop_events.events";
    private static final String BLANK_HEADSIGN = "UPDATE stop_events.events SET headsign = '  '";
    private static final String NULL_DIRECTION = "UPDATE stop_events.events SET direction_id = NULL";
    @Container
    private static final OwnedPostgres DATABASE = new OwnedPostgres();
    private DataSource source;
    private JdbcStopEventStore store;

    @BeforeAll
    static void migrate() { MigrationCommand.migrate(DATABASE.dataSource()); }

    @BeforeEach
    void prepare() throws SQLException {
        source = DATABASE.dataSource();
        store = new JdbcStopEventStore(source);
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(CLEAR)) {
            statement.executeUpdate();
        }
    }

    @Test
    void insertsGeneratedDelayAndDeduplicatesOnThePartitionedUniqueKey() {
        var event = event(2, -1, BASE.plusSeconds(60));
        assertThat(store.insert(List.of(event)).get(event.key())).isEqualTo(event.savedStop());
        assertThat(store.insert(List.of(event)).get(event.key())).isEqualTo(event.savedStop());
        assertThat(count()).isEqualTo(1);
        assertThat(store.storedStops(Set.of(TRIP), DATE)).containsEntry(event.key(), event.savedStop());
    }

    @Test
    void differingConflictRollsBackAllNewRowsInTheTransaction() {
        var original = event(2, 600, BASE.plusSeconds(60));
        store.insert(List.of(original));
        assertThatThrownBy(() -> store.insert(List.of(event(3, 0, BASE.plusSeconds(120)), event(2, 0, BASE.plusSeconds(60)))))
                .isInstanceOf(StoredStopConflict.class);
        assertThat(count()).isEqualTo(1);
        assertThat(store.storedStops(Set.of(TRIP), DATE)).containsOnlyKeys(original.key());
    }

    @Test
    void recoveryIgnoresServiceDatesBeforeTheRequestedStart() {
        var current = event(2, 0, BASE.plusSeconds(60));
        var older = new StopEvent(SOURCE, TRIP, DATE.minusDays(2), 2, SOURCE.scope("B"), "111", "B", null, 0, "End",
                BASE, BASE, 0, null, "KR123", DetectionMethod.STOPPED_AT, false, HASH, 4);
        store.insert(List.of(current, older));
        assertThat(store.storedStops(Set.of(TRIP), DATE.minusDays(1))).containsOnlyKeys(current.key());
    }

    @Test
    void generatedDelayTruncatesNegativeFractionsTowardsZero() {
        var event = new StopEvent(SOURCE, TRIP, DATE, 1, SOURCE.scope("A"), "111", "A", null, 0, "End", BASE,
                BASE.minusNanos(500_000_000), 0, null, "KR123", DetectionMethod.STOPPED_AT, false, HASH, 4);
        assertThat(store.insert(List.of(event)).get(event.key()).delaySeconds()).isZero();
    }

    @Test
    void defaultPartitionAcceptsEventsBeyondTheCreatedHorizon() throws SQLException {
        var event = new StopEvent(SOURCE, TRIP, LocalDate.of(2035, 1, 1), 1, SOURCE.scope("A"), "111", "A", null,
                0, "End", BASE, BASE, 0, null, "KR123", DetectionMethod.STOPPED_AT, false, HASH, 4);
        store.insert(List.of(event));
        try (var connection = source.getConnection(); var query = connection.prepareStatement(PARTITION);
             var row = query.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("stop_events.events_default");
        }
    }

    @Test
    void completenessIsAlsoEnforcedByTheSchema() {
        store.insert(List.of(event(1, 0, BASE)));
        assertThatThrownBy(() -> update(BLANK_HEADSIGN)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(NULL_DIRECTION)).isInstanceOf(SQLException.class);
    }

    private void update(String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private long count() {
        try (var connection = source.getConnection(); var query = connection.prepareStatement(COUNT);
             var row = query.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }
}
