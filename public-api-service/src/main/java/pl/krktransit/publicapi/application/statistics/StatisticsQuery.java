package pl.krktransit.publicapi.application.statistics;

import java.time.LocalDate;
import java.util.Objects;

public record StatisticsQuery(String lineNumber, LocalDate startDate, LocalDate endDate, boolean includeEstimated) {
    public StatisticsQuery {
        Objects.requireNonNull(lineNumber);
        Objects.requireNonNull(startDate);
        Objects.requireNonNull(endDate);
    }
}
