package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;

final class ScheduleWriteLock {
    private static final String ACQUIRE_WRITE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    private ScheduleWriteLock() {}

    static void acquire(Connection connection) throws SQLException {
        // Both namespace keys are fixed for schedule publication and cleanup.
        try (var statement = connection.prepareStatement(ACQUIRE_WRITE_LOCK_SQL)) {
            statement.setInt(1, 0x4b524b54);
            statement.setInt(2, 0x53434844);
            statement.execute();
        }
    }
}
