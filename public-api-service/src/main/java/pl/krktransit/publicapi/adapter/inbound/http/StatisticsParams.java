package pl.krktransit.publicapi.adapter.inbound.http;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.BindParam;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;

public record StatisticsParams(
        @BindParam("start_date") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @BindParam("end_date") @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @BindParam("include_estimated") Boolean includeEstimated) {

    StatisticsQuery query(String lineNumber) {
        return new StatisticsQuery(lineNumber, startDate, endDate, Boolean.TRUE.equals(includeEstimated));
    }
}
