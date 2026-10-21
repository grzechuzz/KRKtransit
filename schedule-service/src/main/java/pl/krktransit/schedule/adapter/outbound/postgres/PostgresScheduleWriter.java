package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import pl.krktransit.schedule.application.port.RowSource;
import pl.krktransit.schedule.application.port.ScheduleWriter;
import pl.krktransit.schedule.domain.schedule.Route;
import pl.krktransit.schedule.domain.schedule.ShapePoint;
import pl.krktransit.schedule.domain.schedule.Stop;
import pl.krktransit.schedule.domain.schedule.StopTime;
import pl.krktransit.schedule.domain.schedule.Trip;

final class PostgresScheduleWriter implements ScheduleWriter {
    private static final String COPY_ROUTES_SQL = "COPY gtfs_schedule.routes "
            + "(version_key, route_id, route_short_name) FROM STDIN WITH (FORMAT CSV)";
    private static final String COPY_STOPS_SQL = "COPY gtfs_schedule.stops "
            + "(version_key, stop_id, stop_name, stop_code, stop_desc, stop_lat, stop_lon) "
            + "FROM STDIN WITH (FORMAT CSV)";
    private static final String COPY_TRIPS_SQL = "COPY gtfs_schedule.trips "
            + "(version_key, trip_id, route_id, service_id, direction_id, headsign, shape_id) "
            + "FROM STDIN WITH (FORMAT CSV)";
    private static final String COPY_STOP_TIMES_SQL = "COPY gtfs_schedule.stop_times "
            + "(version_key, trip_id, stop_sequence, stop_id, arrival_seconds, departure_seconds) "
            + "FROM STDIN WITH (FORMAT CSV)";
    private static final String COPY_SHAPE_POINTS_SQL = "COPY gtfs_schedule.shape_points "
            + "(version_key, shape_id, shape_pt_lat, shape_pt_lon, shape_pt_sequence) "
            + "FROM STDIN WITH (FORMAT CSV)";

    private enum Table { ROUTES, STOPS, TRIPS, STOP_TIMES, SHAPES }

    private final CopyManager manager;
    private final long versionKey;
    private final Thread owner = Thread.currentThread();
    private int completed;
    private boolean writing;
    private boolean failed;

    PostgresScheduleWriter(Connection connection, long versionKey) throws SQLException {
        this.manager = connection.unwrap(PGConnection.class).getCopyAPI();
        this.versionKey = versionKey;
    }

    @Override
    public void writeRoutes(RowSource<Route> rows) {
        copyRows(Table.ROUTES, rows, (copy, row) ->
                copy.write(versionKey, row.id(), row.lineNumber()));
    }

    @Override
    public void writeStops(RowSource<Stop> rows) {
        copyRows(Table.STOPS, rows, (copy, row) -> copy.write(versionKey, row.id(),
                row.name(), row.code(), row.description(), row.latitude(), row.longitude()));
    }

    @Override
    public void writeTrips(RowSource<Trip> rows) {
        copyRows(Table.TRIPS, rows, (copy, row) -> copy.write(versionKey, row.id(),
                row.routeId(), row.serviceId(), row.directionId(), row.headsign(), row.shapeId()));
    }

    @Override
    public void writeStopTimes(RowSource<StopTime> rows) {
        copyRows(Table.STOP_TIMES, rows, (copy, row) -> copy.write(versionKey, row.tripId(), row.sequence(),
                row.stopId(), row.arrivalSeconds(), row.departureSeconds()));
    }

    @Override
    public void writeShapePoints(RowSource<ShapePoint> rows) {
        copyRows(Table.SHAPES, rows, (copy, row) -> copy.write(versionKey, row.shapeId(),
                row.latitude(), row.longitude(), row.sequence()));
    }

    private <T> void copyRows(Table table, RowSource<T> rows, BiConsumer<CopySession, T> write) {
        requireNext(table);
        Objects.requireNonNull(rows, "Schedule row source is required");
        writing = true;
        boolean successful = false;
        try (var copy = openCopy(table)) {
            rows.forEach(row -> write.accept(copy, row));
            copy.finish();
            completed++;
            successful = true;
        } finally {
            writing = false;
            failed = !successful;
        }
    }

    private CopySession openCopy(Table table) {
        PostgresFailures.requireNotInterrupted();
        try {
            return new CopySession(manager.copyIn(copyCommand(table)));
        } catch (SQLException failure) {
            throw PostgresFailures.translate("start COPY for " + table, failure);
        }
    }

    private void requireNext(Table table) {
        if (Thread.currentThread() != owner || writing || failed || table.ordinal() != completed) {
            throw new IllegalStateException("Schedule groups must be written once in dependency order on the calling thread");
        }
    }

    void requireComplete() {
        if (Thread.currentThread() != owner || writing || failed || completed != Table.values().length) {
            throw new IllegalStateException("Every schedule group must be written before publication");
        }
    }

    private static String copyCommand(Table table) {
        return switch (table) {
            case ROUTES -> COPY_ROUTES_SQL;
            case STOPS -> COPY_STOPS_SQL;
            case TRIPS -> COPY_TRIPS_SQL;
            case STOP_TIMES -> COPY_STOP_TIMES_SQL;
            case SHAPES -> COPY_SHAPE_POINTS_SQL;
        };
    }

    private static final class CopySession implements AutoCloseable {
        private final CopyIn copy;
        private final Thread owner = Thread.currentThread();
        private final byte[] buffer = new byte[64 * 1024];
        private int buffered;
        private boolean finished;
        private boolean closed;

        private CopySession(CopyIn copy) {
            this.copy = copy;
        }

        private void write(Object... values) {
            requireOpen();
            PostgresFailures.requireNotInterrupted();
            try {
                append(CopyCsvEncoder.encode(values));
            } catch (SQLException failure) {
                throw PostgresFailures.translate("write schedule COPY rows", failure);
            }
        }

        private void append(byte[] bytes) throws SQLException {
            if (bytes.length >= buffer.length) {
                flush();
                copy.writeToCopy(bytes, 0, bytes.length);
                return;
            }
            if (bytes.length > buffer.length - buffered) {
                flush();
            }
            System.arraycopy(bytes, 0, buffer, buffered, bytes.length);
            buffered += bytes.length;
        }

        private void flush() throws SQLException {
            if (buffered > 0) {
                copy.writeToCopy(buffer, 0, buffered);
                buffered = 0;
            }
        }

        private void finish() {
            requireOpen();
            try {
                flush();
                copy.endCopy();
                finished = true;
            } catch (SQLException failure) {
                throw PostgresFailures.translate("complete schedule COPY", failure);
            }
        }

        private void requireOpen() {
            if (Thread.currentThread() != owner || finished || closed) {
                throw new IllegalStateException("Schedule rows can only be emitted synchronously while copying");
            }
        }

        @Override
        public void close() {
            closed = true;
            if (finished || !copy.isActive()) {
                return;
            }
            try {
                copy.cancelCopy();
            } catch (SQLException failure) {
                throw PostgresFailures.translate("cancel unfinished COPY", failure);
            }
        }
    }
}
