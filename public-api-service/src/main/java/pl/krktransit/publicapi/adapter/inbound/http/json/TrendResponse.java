package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record TrendResponse(String lineNumber, String startDate, String endDate, List<TrendDayItem> days) {
    public TrendResponse { days = List.copyOf(days); }
}
