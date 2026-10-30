package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record TripStopsResponse(String tripId, List<TripStopItem> stops) {
    public TripStopsResponse { stops = List.copyOf(stops); }
}
