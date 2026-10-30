package pl.krktransit.publicapi.application.schedule;

import java.util.List;

public record Shape(String shapeId, List<ShapePoint> points) {
    public Shape { points = List.copyOf(points); }

    public boolean hasPoints() { return !points.isEmpty(); }
}
