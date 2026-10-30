package pl.krktransit.publicapi.adapter.inbound.http;

import pl.krktransit.publicapi.adapter.inbound.http.json.ShapePointItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.ShapeResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.TripStopItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.TripStopsResponse;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.ShapePoint;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;

final class ScheduleResponseMapper {
    TripStopsResponse tripStops(TripStops trip) {
        return new TripStopsResponse(trip.tripId(), trip.stops().stream().map(this::stop).toList());
    }

    ShapeResponse shape(Shape shape) {
        return new ShapeResponse(shape.shapeId(), shape.points().stream().map(this::point).toList());
    }

    private TripStopItem stop(TripStop stop) {
        return new TripStopItem(stop.stopId(), stop.stopName(), stop.stopDescription(), stop.latitude(), stop.longitude(),
                stop.sequence());
    }

    private ShapePointItem point(ShapePoint point) {
        return new ShapePointItem(point.latitude(), point.longitude(), point.sequence());
    }
}
