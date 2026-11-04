package pl.krktransit.schedule.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.domain.lineroute.LineDirection;
import pl.krktransit.schedule.domain.lineroute.RoutePoint;
import pl.krktransit.schedule.domain.lineroute.RouteSegment;
import pl.krktransit.schedule.domain.lineroute.RouteStop;
import pl.krktransit.schedule.domain.lineroute.SegmentKey;
import pl.krktransit.schedule.domain.lineroute.StopKey;

class GrpcResponseMapperTest {
    @Test
    void mapsLineRoutesWithoutLosingZeroCoordinatesOrDuplicateGeometryPoints() {
        var from = StopKey.of("A", null);
        var to = StopKey.of("B", "01");
        var points = List.of(new RoutePoint(0, 0), new RoutePoint(0, 0));
        var direction = new LineDirection("End", 12, List.of(new RouteStop(from, "A", "", 0, 0)),
                List.of(new RouteSegment(new SegmentKey(from, to), points)));
        var result = new GrpcResponseMapper().lineRoutes(List.of(direction));
        assertThat(result.getDirectionsList()).singleElement().satisfies(row -> {
            assertThat(row.getTripsCount()).isEqualTo(12);
            assertThat(row.getStops(0).hasLatitude()).isTrue();
            assertThat(row.getStops(0).getStopDesc()).isEmpty();
            assertThat(row.getSegments(0).getFromStopKey()).isEqualTo("a|");
            assertThat(row.getSegments(0).getToStopKey()).isEqualTo("b|01");
            assertThat(row.getSegments(0).getPointsList()).hasSize(2);
        });
    }
}
