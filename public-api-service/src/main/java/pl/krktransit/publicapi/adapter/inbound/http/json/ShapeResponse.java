package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record ShapeResponse(String shapeId, List<ShapePointItem> points) {
    public ShapeResponse { points = List.copyOf(points); }
}
