package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record RouteDelayResponse(String lineNumber, String startDate, String endDate, List<RouteDelayItem> maxRouteDelay,
                                 long tripsAnalyzed) {
    public RouteDelayResponse { maxRouteDelay = List.copyOf(maxRouteDelay); }
}
