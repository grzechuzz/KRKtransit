package pl.krktransit.schedule.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.application.importing.ImportFailure;

class PostgresFailuresTest {
    @Test
    void constraintViolationsRejectSourceDataWithoutBecomingTransientFailures() {
        var failure = PostgresFailures.translate("publish", new SQLException("duplicate", "23505"));

        assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.VALIDATION);
    }

    @Test
    void brokenConnectionIsAnOperationalPersistenceFailure() {
        var failure = PostgresFailures.translate("publish", new SQLException("disconnected", "08006"));

        assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.PERSISTENCE);
    }
}
