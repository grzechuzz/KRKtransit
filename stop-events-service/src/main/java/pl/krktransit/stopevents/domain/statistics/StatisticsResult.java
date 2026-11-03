package pl.krktransit.stopevents.domain.statistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public sealed interface StatisticsResult {
    record DelayMap(List<MapSegment> segments) implements StatisticsResult {
        public DelayMap { segments = List.copyOf(segments); }
    }

    record MapSegment(String fromStopKey, String toStopKey, List<BandDelay> delays) {
        public MapSegment { delays = List.copyOf(delays); }
    }

    record BandDelay(DelayBand band, long samples, double meanSeconds, double medianSeconds, double p90Seconds) {}

    record MaxDelay(long tripsAnalyzed, List<Segment> segments) implements StatisticsResult {
        public MaxDelay { segments = List.copyOf(segments); }
    }

    record RouteDelay(long tripsAnalyzed, List<Route> routes) implements StatisticsResult {
        public RouteDelay { routes = List.copyOf(routes); }
    }

    record Punctuality(long total, long onTime, long slightlyDelayed, long delayed) implements StatisticsResult {
        public double onTimePercent() { return percent(onTime); }
        public double slightlyDelayedPercent() { return percent(slightlyDelayed); }
        public double delayedPercent() { return percent(delayed); }

        private double percent(long count) {
            if (total == 0) return 0;
            double percentage = (double) count / total * 100;
            return new BigDecimal(percentage).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
        }
    }

    record Trend(List<Day> days) implements StatisticsResult {
        public Trend { days = List.copyOf(days); }
    }

    record Day(LocalDate date, double averageDelaySeconds, long tripsCount) {}

    record Segment(String tripId, LocalDate serviceDate, String lineNumber, String vehicleNumber,
                   String fromStop, String toStop, int fromSequence, int toSequence,
                   Instant fromPlannedTime, Instant fromEventTime, Instant toPlannedTime, Instant toEventTime,
                   int delayGeneratedSeconds, String headsign, boolean estimated) {}

    record Route(String tripId, LocalDate serviceDate, String lineNumber, String vehicleNumber,
                 String firstStop, String lastStop, Instant firstPlannedTime, Instant firstEventTime,
                 Instant lastPlannedTime, Instant lastEventTime, int startDelaySeconds, int endDelaySeconds,
                 int delayGeneratedSeconds, String headsign, boolean estimated) {}
}
