package pl.krktransit.publicapi.adapter.inbound.http.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.delaymap.DelayMap;
import pl.krktransit.publicapi.application.delaymap.DelayMapDirection;
import pl.krktransit.publicapi.application.delaymap.DelayMapRouteSegment;
import pl.krktransit.publicapi.application.schedule.RoutePoint;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;

class DelayMapResponseMapperTest {
    @Test
    void preservesCoordinateOrderAndEmptyMeasurements() {
        var query = new StatisticsQuery("198", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), false);
        var segment = new DelayMapRouteSegment("a|", "b|", List.of(new RoutePoint(50, 19), new RoutePoint(51, 20)), List.of());
        var response = new DelayMapResponseMapper().response(query,
                new DelayMap(List.of(new DelayMapDirection("End", 2, List.of(), List.of(segment)))));
        assertThat(response.lineNumber()).isEqualTo("198");
        assertThat(response.directions().getFirst().segments().getFirst().points()).containsExactly(List.of(50.0, 19.0), List.of(51.0, 20.0));
        assertThat(response.directions().getFirst().segments().getFirst().delays()).isEmpty();
    }
}
