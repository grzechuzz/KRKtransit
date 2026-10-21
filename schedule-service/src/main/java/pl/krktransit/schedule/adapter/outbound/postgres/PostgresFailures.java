package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.SQLException;
import pl.krktransit.schedule.application.importing.ImportFailure;

final class PostgresFailures {
    private PostgresFailures() {}

    static ImportFailure translate(String operation, SQLException failure) {
        String state = failure.getSQLState();
        boolean invalidData = state != null && (state.startsWith("22") || state.startsWith("23"));
        var kind = invalidData ? ImportFailure.Kind.VALIDATION : ImportFailure.Kind.PERSISTENCE;
        return new ImportFailure(kind, "Schedule database failed to " + operation, failure);
    }

    static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "Schedule operation interrupted");
        }
    }
}
