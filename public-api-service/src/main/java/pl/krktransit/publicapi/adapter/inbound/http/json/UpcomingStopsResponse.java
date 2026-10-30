package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record UpcomingStopsResponse(String tripId, String status, String observedAt, List<UpcomingStopItem> stops) {
    public UpcomingStopsResponse { stops = List.copyOf(stops); }
}
