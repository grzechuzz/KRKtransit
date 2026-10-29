package pl.krktransit.stopevents.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.support.OwnedPostgres;

@Testcontainers
class AdvisoryDetectorOwnershipIT {
    private static final String TERMINATE_OTHERS = """
            SELECT pg_terminate_backend(pid) FROM pg_locks
            WHERE locktype = 'advisory' AND pid <> pg_backend_pid()
            """;
    @Container
    private static final OwnedPostgres DATABASE = new OwnedPostgres();

    @Test
    void onlyOneDetectorHoldsTheStreamsUntilItCloses() {
        try (var first = new AdvisoryDetectorOwnership(DATABASE.dataSource());
             var second = new AdvisoryDetectorOwnership(DATABASE.dataSource())) {
            first.hold();
            first.hold();
            assertThatThrownBy(second::hold).isInstanceOf(StateIntegrityFailure.class);
            first.close();
            assertThatCode(second::hold).doesNotThrowAnyException();
            assertThatThrownBy(first::hold).isInstanceOf(StateIntegrityFailure.class);
        }
    }

    @Test
    void aLostSessionIsReportedOnceAndThenReacquired() throws SQLException {
        try (var ownership = new AdvisoryDetectorOwnership(DATABASE.dataSource())) {
            ownership.hold();
            try (var connection = DATABASE.dataSource().getConnection();
                 var statement = connection.prepareStatement(TERMINATE_OTHERS)) {
                statement.execute();
            }
            assertThatThrownBy(ownership::hold).isInstanceOf(StorageFailure.class);
            assertThatCode(ownership::hold).doesNotThrowAnyException();
        }
    }
}
