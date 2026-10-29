package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import pl.krktransit.stopevents.application.port.DetectorOwnership;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.application.port.StorageFailure;

public final class AdvisoryDetectorOwnership implements DetectorOwnership, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AdvisoryDetectorOwnership.class.getName());
    private static final String ACQUIRE = "SELECT pg_try_advisory_lock(?, ?)";
    private static final int NAMESPACE = 0x4b524b54;
    private static final int DETECTOR = 0x53544556;
    private final DataSource source;
    private Connection connection;

    public AdvisoryDetectorOwnership(DataSource source) { this.source = source; }

    @Override
    public void hold() {
        if (connection == null) {
            connection = acquire();
            return;
        }
        if (valid()) return;
        release();
        throw new StorageFailure(StorageFailure.Kind.POSTGRES, "Detector ownership was lost", null);
    }

    private Connection acquire() {
        Connection candidate;
        try { candidate = source.getConnection(); }
        catch (SQLException failure) { throw unavailable(failure); }
        try {
            if (locked(candidate)) return candidate;
        } catch (SQLException failure) {
            close(candidate);
            throw unavailable(failure);
        }
        close(candidate);
        throw new StateIntegrityFailure("Another detector owns the stop-event streams");
    }

    private boolean locked(Connection candidate) throws SQLException {
        try (var statement = candidate.prepareStatement(ACQUIRE)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, DETECTOR);
            try (var rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    private boolean valid() {
        try { return connection.isValid(2); }
        catch (SQLException failure) { return false; }
    }

    private StorageFailure unavailable(SQLException failure) {
        return new StorageFailure(StorageFailure.Kind.POSTGRES, "Detector ownership is unavailable", failure);
    }

    private void release() {
        if (connection != null) close(connection);
        connection = null;
    }

    private void close(Connection value) {
        try { value.close(); }
        catch (SQLException failure) { LOG.log(System.Logger.Level.WARNING, "Ownership connection close failed", failure); }
    }

    @Override
    public void close() { release(); }
}
