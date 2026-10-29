package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

final class EventTransaction implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(EventTransaction.class.getName());
    private final Connection connection;
    private boolean committed;

    private EventTransaction(Connection connection) { this.connection = connection; }

    static EventTransaction open(DataSource source) throws SQLException {
        var connection = source.getConnection();
        try {
            connection.setAutoCommit(false);
            return new EventTransaction(connection);
        } catch (SQLException failure) {
            try { connection.close(); }
            catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    Connection connection() { return connection; }
    void commit() throws SQLException { connection.commit(); committed = true; }

    @Override
    public void close() {
        try {
            if (!committed) connection.rollback();
        } catch (SQLException failure) {
            LOG.log(System.Logger.Level.ERROR, "Stop-event rollback failed", failure);
        } finally {
            closeConnection();
        }
    }

    private void closeConnection() {
        try { connection.close(); }
        catch (SQLException failure) { LOG.log(System.Logger.Level.WARNING, "Stop-event connection close failed", failure); }
    }
}
