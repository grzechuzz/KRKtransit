package pl.krktransit.publicapi.adapter.inbound.http.json;

public record UpcomingStopItem(String stopId, String stopName, String stopDesc, int sequence, String scheduledArrival,
                               String predictedArrival, String predictionSource) {}
