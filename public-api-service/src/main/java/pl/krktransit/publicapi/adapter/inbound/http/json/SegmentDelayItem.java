package pl.krktransit.publicapi.adapter.inbound.http.json;

public record SegmentDelayItem(String tripId, String lineNumber, String vehicleNumber, String fromStop, String toStop,
                               int fromSequence, int toSequence, String fromPlannedTime, String fromEventTime,
                               String toPlannedTime, String toEventTime, int delayGeneratedSeconds, String headsign,
                               String serviceDate, boolean isEstimated) {}
