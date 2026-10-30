package pl.krktransit.publicapi.application.statistics;

import java.util.List;

public record Trend(List<TrendDay> days) {
    public Trend { days = List.copyOf(days); }
}
