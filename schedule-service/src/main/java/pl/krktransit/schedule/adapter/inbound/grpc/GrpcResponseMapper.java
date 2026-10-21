package pl.krktransit.schedule.adapter.inbound.grpc;

import pl.krktransit.schedule.application.querying.GeometryPoint;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ShapePoint;
import pl.krktransit.schedule.contracts.v1.TripStop;

final class GrpcResponseMapper {
    GetTripContextResponse trip(TripContext context) {
        var response = GetTripContextResponse.newBuilder()
                .setHash(context.hash().value()).setTripId(context.tripId())
                .setRouteId(context.routeId()).setLineNumber(context.lineNumber())
                .setMaxStopSequence(context.maxStopSequence());
        if (context.directionId() != null) response.setDirectionId(context.directionId());
        if (context.headsign() != null) response.setHeadsign(context.headsign());
        if (context.shapeId() != null) response.setShapeId(context.shapeId());
        context.stops().forEach(stop -> response.addStops(stop(stop)));
        return response.build();
    }

    private TripStop stop(pl.krktransit.schedule.application.querying.TripStop source) {
        var stop = TripStop.newBuilder().setStopId(source.stopId()).setStopName(source.stopName())
                .setStopSequence(source.sequence()).setArrivalSeconds(source.arrivalSeconds())
                .setDepartureSeconds(source.departureSeconds());
        if (source.stopDesc() != null) stop.setStopDesc(source.stopDesc());
        if (source.latitude() != null) stop.setLatitude(source.latitude());
        if (source.longitude() != null) stop.setLongitude(source.longitude());
        return stop.build();
    }

    GetShapeResponse shape(ShapeContext context) {
        return GetShapeResponse.newBuilder().setHash(context.hash().value()).setShapeId(context.shapeId())
                .addAllPoints(context.points().stream().map(this::point).toList()).build();
    }

    private ShapePoint point(GeometryPoint source) {
        return ShapePoint.newBuilder().setLatitude(source.latitude()).setLongitude(source.longitude())
                .setSequence(source.sequence()).build();
    }
}
