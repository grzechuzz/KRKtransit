package pl.krktransit.publicapi.application.statistics;

import java.util.List;

public record MaxDelayRanking(List<SegmentDelay> segments, long tripsAnalyzed) {
    public MaxDelayRanking { segments = List.copyOf(segments); }
}
