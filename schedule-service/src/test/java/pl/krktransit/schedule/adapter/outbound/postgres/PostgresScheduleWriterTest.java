package pl.krktransit.schedule.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.domain.schedule.Route;

class PostgresScheduleWriterTest {
    private final Connection connection = mock(Connection.class);
    private final PGConnection postgres = mock(PGConnection.class);
    private final CopyManager manager = mock(CopyManager.class);
    private final CopyIn copy = mock(CopyIn.class);

    @BeforeEach
    void postgresCopyIsAvailable() throws SQLException {
        when(connection.unwrap(PGConnection.class)).thenReturn(postgres);
        when(postgres.getCopyAPI()).thenReturn(manager);
        when(manager.copyIn(anyString())).thenReturn(copy);
        when(copy.isActive()).thenReturn(true);
    }

    @Test
    void consumesSourceOnceOnCallingThreadAndFlushesBeforeFinishingCopy() throws SQLException {
        var writer = new PostgresScheduleWriter(connection, 7);
        var invocations = new AtomicInteger();
        Thread caller = Thread.currentThread();

        writer.writeRoutes(receiver -> {
            invocations.incrementAndGet();
            assertThat(Thread.currentThread()).isSameAs(caller);
            receiver.accept(new Route("krakow:mpk:001", "01"));
            verifyNoBytesSentYet();
        });

        assertThat(invocations).hasValue(1);
        byte[] expected = CopyCsvEncoder.encode(7L, "krakow:mpk:001", "01");
        var captured = ArgumentCaptor.forClass(byte[].class);
        var order = inOrder(copy);
        order.verify(copy).writeToCopy(captured.capture(), eq(0), eq(expected.length));
        order.verify(copy).endCopy();
        verify(copy, never()).cancelCopy();
        assertThat(captured.getValue()).startsWith(expected);
    }

    @Test
    void lateSourceFailureCancelsStartedCopyWithoutFinishingIt() throws SQLException {
        var writer = new PostgresScheduleWriter(connection, 7);
        var failure = new ImportFailure(ImportFailure.Kind.VALIDATION, "Malformed later CSV row");

        assertThatThrownBy(() -> writer.writeRoutes(receiver -> {
            receiver.accept(largeRoute());
            verifyBytesWereSent();
            throw failure;
        })).isSameAs(failure);

        verify(copy).cancelCopy();
        verify(copy, never()).endCopy();
    }

    @Test
    void cancellationFailureIsSuppressedOnTheOriginalSourceFailure() throws SQLException {
        var writer = new PostgresScheduleWriter(connection, 7);
        var original = new ImportFailure(ImportFailure.Kind.VALIDATION, "Malformed later CSV row");
        var cancellation = new SQLException("COPY cancellation failed", "08006");
        doThrow(cancellation).when(copy).cancelCopy();

        assertThatThrownBy(() -> writer.writeRoutes(receiver -> {
            receiver.accept(largeRoute());
            throw original;
        })).isSameAs(original);

        assertThat(original.getSuppressed()).singleElement()
                .isInstanceOfSatisfying(ImportFailure.class, cleanup -> {
                    assertThat(cleanup.kind()).isEqualTo(ImportFailure.Kind.PERSISTENCE);
                    assertThat(cleanup).hasCause(cancellation);
                });
        verify(copy, never()).endCopy();
    }

    @Test
    void rejectsEmissionAfterTheSourceHasReturned() throws SQLException {
        var writer = new PostgresScheduleWriter(connection, 7);
        var escaped = new AtomicReference<Consumer<? super Route>>();
        writer.writeRoutes(escaped::set);

        assertThatThrownBy(() -> escaped.get().accept(new Route("krakow:mpk:001", "01")))
                .isInstanceOf(IllegalStateException.class);

        verify(copy, never()).writeToCopy(any(), anyInt(), anyInt());
        verify(copy).endCopy();
    }

    private Route largeRoute() {
        return new Route("krakow:mpk:001", "A".repeat(70_000));
    }

    private void verifyNoBytesSentYet() {
        try {
            verify(copy, never()).writeToCopy(any(), anyInt(), anyInt());
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    private void verifyBytesWereSent() {
        try {
            verify(copy).writeToCopy(any(), eq(0), anyInt());
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }
}
