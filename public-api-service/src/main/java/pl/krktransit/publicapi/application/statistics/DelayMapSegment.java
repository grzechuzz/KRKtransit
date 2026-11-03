package pl.krktransit.publicapi.application.statistics;

import java.util.List;

public record DelayMapSegment(String fromStopKey, String toStopKey, List<BandDelay> delays) {
    public DelayMapSegment { delays = List.copyOf(delays); }
}
