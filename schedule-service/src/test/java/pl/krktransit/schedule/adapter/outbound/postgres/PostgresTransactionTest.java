package pl.krktransit.schedule.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresTransactionTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);

    @BeforeEach
    void connects() throws SQLException {
        when(source.getConnection()).thenReturn(connection);
    }

    @Test
    void connectionCleanupCannotTurnCommittedPublicationIntoFailure() throws SQLException {
        doThrow(new SQLException("Connection closed by server")).when(connection).close();

        assertThatCode(() -> {
            try (var transaction = PostgresTransaction.write(source)) {
                transaction.commit();
            }
        }).doesNotThrowAnyException();

        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void failedTransactionInitializationClosesConnection() throws SQLException {
        var failure = new SQLException("Cannot initialize transaction");
        doThrow(failure).when(connection).setAutoCommit(false);

        assertThatThrownBy(() -> PostgresTransaction.write(source)).isSameAs(failure);
        verify(connection).close();
    }
}
