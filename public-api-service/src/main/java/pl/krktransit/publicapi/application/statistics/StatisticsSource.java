package pl.krktransit.publicapi.application.statistics;

public interface StatisticsSource {
    MaxDelayRanking maxDelay(StatisticsQuery query);

    RouteDelayRanking routeDelay(StatisticsQuery query);

    Punctuality punctuality(StatisticsQuery query);

    Trend trend(StatisticsQuery query);
}
