package pl.krktransit.publicapi.application.statistics;

public final class LineStatistics {
    private final StatisticsSource source;

    public LineStatistics(StatisticsSource source) { this.source = source; }

    public MaxDelayRanking maxDelay(StatisticsQuery query) { return source.maxDelay(query); }

    public RouteDelayRanking routeDelay(StatisticsQuery query) { return source.routeDelay(query); }

    public Punctuality punctuality(StatisticsQuery query) { return source.punctuality(query); }

    public Trend trend(StatisticsQuery query) { return source.trend(query); }
}
