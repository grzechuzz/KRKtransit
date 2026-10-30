package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record MaxDelayResponse(String lineNumber, String startDate, String endDate, List<SegmentDelayItem> maxDelay,
                               long tripsAnalyzed) {
    public MaxDelayResponse { maxDelay = List.copyOf(maxDelay); }
}
