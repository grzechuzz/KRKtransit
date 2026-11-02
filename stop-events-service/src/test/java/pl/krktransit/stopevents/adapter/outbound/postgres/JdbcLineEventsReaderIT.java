package pl.krktransit.stopevents.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.stopevents.bootstrap.MigrationCommand;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.lineevents.LineEvent;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.support.OwnedPostgres;

@Testcontainers
class JdbcLineEventsReaderIT {
    private static final String CLEAR = "DELETE FROM stop_events.events";
    private static final String SET_CREATED = """
            UPDATE stop_events.events SET created_at = ? WHERE trip_id = ? AND stop_sequence = ?
            """;
    private static final String INDEX_DEFINITION = """
            SELECT indexdef FROM pg_indexes WHERE schemaname = 'stop_events' AND indexname = 'events_line_created'
            """;
    private static final String DISABLE_SEQUENTIAL_SCANS = "SET enable_seqscan = off";
    private static final String EXPLAIN_LINE_EVENTS = """
            EXPLAIN SELECT id FROM stop_events.events
            WHERE city_id = 'krakow' AND line_number = '139'
                AND (created_at, id) > ('2026-10-25T12:00:00Z'::timestamptz, 5::bigint)
            ORDER BY created_at, id LIMIT 3
            """;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 25);
    private static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");
    private static final SourceId SOURCE = new SourceId("krakow", "mpk-bus");
    @Container private static final OwnedPostgres DATABASE = new OwnedPostgres();
    private JdbcLineEventsReader reader;
    private JdbcStopEventStore writer;

    @BeforeAll
    static void migrate() { MigrationCommand.migrate(DATABASE.dataSource()); }

    @BeforeEach
    void prepare() throws SQLException {
        reader = new JdbcLineEventsReader(DATABASE.dataSource());
        writer = new JdbcStopEventStore(DATABASE.dataSource());
        try (var connection = DATABASE.dataSource().getConnection(); var statement = connection.prepareStatement(CLEAR)) {
            statement.executeUpdate();
        }
    }

    @Test
    void pagesInCreatedAtAndIdOrderWithoutGapsOrDuplicatesAcrossEqualTimestamps() throws SQLException {
        for (int sequence = 1; sequence <= 12; sequence++) {
            insert(SOURCE, "trip", sequence, "139");
            created("trip", sequence, sequence <= 4 ? T0.plusSeconds(2) : sequence <= 9 ? T0.plusSeconds(1) : T0);
        }
        var sequences = new ArrayList<Integer>();
        var hasMore = new ArrayList<Boolean>();
        var cursor = new LineEventsQuery("krakow", "139", T0.minusSeconds(1), OptionalLong.empty(), 4);
        LineEventsPage page;
        do {
            page = reader.read(cursor);
            page.events().forEach(event -> sequences.add(event.stopSequence()));
            hasMore.add(page.hasMore());
            var last = page.last().orElseThrow();
            cursor = new LineEventsQuery("krakow", "139", last.createdAt(), OptionalLong.of(last.id()), 4);
        } while (page.hasMore());
        assertThat(sequences).containsExactly(10, 11, 12, 5, 6, 7, 8, 9, 1, 2, 3, 4);
        assertThat(hasMore).containsExactly(true, true, false);
        assertThat(reader.read(cursor).events()).isEmpty();
    }

    @Test
    void filtersByCityAndLine() throws SQLException {
        insert(SOURCE, "kept", 2, "139");
        insert(new SourceId("krakow", "mobilis-bus"), "other-source", 2, "139");
        insert(SOURCE, "other-line", 2, "179");
        insert(new SourceId("warsaw", "mpk-bus"), "other-city", 2, "139");
        var events = reader.read(new LineEventsQuery("krakow", "139", T0.minusSeconds(3600), OptionalLong.empty(), 10)).events();
        assertThat(events).extracting(LineEvent::tripId)
                .containsExactlyInAnyOrder("krakow:mpk-bus:kept", "krakow:mobilis-bus:other-source");
    }

    @Test
    void anAbsentIdStartsStrictlyAfterTheTimestampAndAnIdContinuesWithinIt() throws SQLException {
        insert(SOURCE, "trip", 2, "139");
        insert(SOURCE, "trip", 3, "139");
        created("trip", 2, T0);
        created("trip", 3, T0);
        assertThat(read(T0, OptionalLong.empty())).isEmpty();
        var all = read(T0.minusNanos(1_000), OptionalLong.empty());
        assertThat(all).extracting(LineEvent::stopSequence).containsExactly(2, 3);
        assertThat(read(T0, OptionalLong.of(0))).hasSize(2);
        assertThat(read(T0, OptionalLong.of(all.getFirst().id()))).extracting(LineEvent::stopSequence).containsExactly(3);
    }

    @Test
    void subMicrosecondCursorsAreTruncatedRatherThanRoundedPastAStoredEvent() throws SQLException {
        insert(SOURCE, "trip", 2, "139");
        created("trip", 2, T0.plusNanos(1_000));
        assertThat(read(T0.plusNanos(600), OptionalLong.empty())).hasSize(1);
        assertThat(read(T0.plusNanos(1_000), OptionalLong.empty())).isEmpty();
    }

    @Test
    void mapsStoredColumnsIncludingHeadsignAndAbsentDescription() throws SQLException {
        var planned = DATE.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(43_200);
        writer.insert(List.of(new StopEvent(SOURCE, SOURCE.scope("trip"), DATE, 0, SOURCE.scope("stop"), "139",
                "Rondo Mogilskie", null, 1, "Kombinat", planned, planned.plusSeconds(0), 0, "vehicle", "DN041",
                DetectionMethod.TIMEOUT, true, "hash", 25)));
        created("trip", 0, T0.plusNanos(123_456_000));
        var event = read(T0, OptionalLong.empty()).getFirst();
        assertThat(event.sourceId()).isEqualTo("mpk-bus");
        assertThat(event.tripId()).isEqualTo("krakow:mpk-bus:trip");
        assertThat(event.serviceDate()).isEqualTo(DATE);
        assertThat(event.stopSequence()).isZero();
        assertThat(event.maxStopSequence()).isEqualTo(25);
        assertThat(event.stopId()).isEqualTo("krakow:mpk-bus:stop");
        assertThat(event.stopName()).isEqualTo("Rondo Mogilskie");
        assertThat(event.stopDescription()).isNull();
        assertThat(event.plannedTime()).isEqualTo(planned);
        assertThat(event.eventTime()).isEqualTo(planned);
        assertThat(event.delaySeconds()).isZero();
        assertThat(event.method()).isEqualTo(DetectionMethod.TIMEOUT);
        assertThat(event.estimated()).isTrue();
        assertThat(event.createdAt()).isEqualTo(T0.plusNanos(123_456_000));
        assertThat(event.headsign()).isEqualTo("Kombinat");
    }

    @Test
    void theMigrationIndexServesTheLineCursorCondition() throws SQLException {
        insert(SOURCE, "trip", 2, "139");
        try (var connection = DATABASE.dataSource().getConnection()) {
            try (var definition = connection.prepareStatement(INDEX_DEFINITION); var rows = definition.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).endsWith("USING btree (city_id, line_number, created_at, id)");
            }
            try (var disable = connection.prepareStatement(DISABLE_SEQUENTIAL_SCANS)) { disable.execute(); }
            var plan = new StringBuilder();
            try (var explain = connection.prepareStatement(EXPLAIN_LINE_EVENTS); var rows = explain.executeQuery()) {
                while (rows.next()) plan.append(rows.getString(1)).append('\n');
            }
            assertThat(plan.toString()).contains("Merge Append").contains("Index Cond").contains("ROW(created_at, id) >")
                    .doesNotContain("Sort  (").doesNotContain("Seq Scan");
        }
    }

    private List<LineEvent> read(Instant createdAfter, OptionalLong afterId) {
        return reader.read(new LineEventsQuery("krakow", "139", createdAfter, afterId, 10)).events();
    }

    private void insert(SourceId source, String trip, int sequence, String line) {
        var planned = DATE.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(sequence * 600L);
        writer.insert(List.of(new StopEvent(source, source.scope(trip), DATE, sequence, source.scope("stop" + sequence),
                line, "Stop " + sequence, "01", 0, "End", planned, planned.plusSeconds(30), 30, null, "PLATE",
                DetectionMethod.STOPPED_AT, false, "hash", 20)));
    }

    private void created(String trip, int sequence, Instant createdAt) throws SQLException {
        try (var connection = DATABASE.dataSource().getConnection(); var update = connection.prepareStatement(SET_CREATED)) {
            update.setObject(1, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            update.setString(2, SOURCE.scope(trip));
            update.setInt(3, sequence);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }
}
