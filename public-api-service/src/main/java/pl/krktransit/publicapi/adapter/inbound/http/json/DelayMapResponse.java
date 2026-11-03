package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record DelayMapResponse(String lineNumber, String startDate, String endDate, List<DelayMapDirectionItem> directions) {
    public DelayMapResponse { directions = List.copyOf(directions); }
}
