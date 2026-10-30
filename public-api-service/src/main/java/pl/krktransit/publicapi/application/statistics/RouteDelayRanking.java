package pl.krktransit.publicapi.application.statistics;

import java.util.List;

public record RouteDelayRanking(List<RouteDelay> routes, long tripsAnalyzed) {
    public RouteDelayRanking { routes = List.copyOf(routes); }
}
