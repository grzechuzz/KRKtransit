package pl.krktransit.publicapi.adapter.inbound.http.json;

public record RouteDelayItem(String tripId, String lineNumber, String vehicleNumber, String firstStop, String lastStop,
                             String firstPlannedTime, String firstEventTime, String lastPlannedTime, String lastEventTime,
                             int startDelaySeconds, int endDelaySeconds, int delayGeneratedSeconds, String headsign,
                             String serviceDate, boolean isEstimated) {}
