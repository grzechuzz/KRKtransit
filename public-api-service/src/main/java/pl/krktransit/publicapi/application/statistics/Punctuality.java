package pl.krktransit.publicapi.application.statistics;

public record Punctuality(long totalStops, long onTimeCount, double onTimePercent, long slightlyDelayedCount,
                          double slightlyDelayedPercent, long delayedCount, double delayedPercent) {}
