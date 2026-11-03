package pl.krktransit.publicapi.application.statistics;

import java.util.List;

public record DelayMapStatistics(List<DelayMapSegment> segments) {
    public DelayMapStatistics { segments = List.copyOf(segments); }
}
