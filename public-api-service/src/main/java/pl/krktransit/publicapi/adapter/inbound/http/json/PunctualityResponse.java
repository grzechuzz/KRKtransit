package pl.krktransit.publicapi.adapter.inbound.http.json;

public record PunctualityResponse(String lineNumber, String startDate, String endDate, long totalStops,
                                  long onTimeCount, double onTimePercent, long slightlyDelayedCount,
                                  double slightlyDelayedPercent, long delayedCount, double delayedPercent) {}
