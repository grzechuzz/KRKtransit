package pl.krktransit.publicapi.adapter.inbound.http.json;

public record TripStopItem(String stopId, String stopName, String stopDesc, Double latitude, Double longitude,
                           int sequence) {}
