package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

final class PostgresTransaction implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(PostgresTransaction.class.getName());
    private final Connection connection;
    private boolean committed;

    private PostgresTransaction(Connection connection) {
        this.connection = connection;
    }

    static PostgresTransaction write(DataSource source) throws SQLException {
        return open(source, false, Connection.TRANSACTION_READ_COMMITTED);
    }

    static PostgresTransaction read(DataSource source) throws SQLException {
        return open(source, true, Connection.TRANSACTION_REPEATABLE_READ);
    }

    private static PostgresTransaction open(DataSource source, boolean readOnly, int isolation)
            throws SQLException {
        Connection connection = source.getConnection();
        try {
            connection.setReadOnly(readOnly);
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            return new PostgresTransaction(connection);
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    Connection connection() {
        return connection;
    }

    void commit() throws SQLException {
        connection.commit();
        committed = true;
    }

    @Override
    public void close() {
        if (!committed) {
            rollback();
        }
        try {
            connection.close();
        } catch (SQLException failure) {
            LOG.log(System.Logger.Level.WARNING, "Failed to close schedule connection", failure);
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            LOG.log(System.Logger.Level.WARNING, "Failed to roll back schedule transaction", failure);
        }
    }
}
