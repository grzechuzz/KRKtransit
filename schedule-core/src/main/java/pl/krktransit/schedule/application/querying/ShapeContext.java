package pl.krktransit.schedule.application.querying;

import java.util.List;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public record ShapeContext(ScheduleHash hash, String shapeId, List<GeometryPoint> points) {
    public ShapeContext { points = List.copyOf(points); }
}
