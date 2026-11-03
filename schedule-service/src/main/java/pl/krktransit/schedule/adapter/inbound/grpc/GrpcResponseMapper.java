package pl.krktransit.schedule.adapter.inbound.grpc;

import java.util.List;
import pl.krktransit.schedule.application.querying.GeometryPoint;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.contracts.v1.GetLineRoutesResponse;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.RoutePoint;
import pl.krktransit.schedule.contracts.v1.RouteSegment;
import pl.krktransit.schedule.contracts.v1.RouteStop;
import pl.krktransit.schedule.contracts.v1.ShapePoint;
import pl.krktransit.schedule.contracts.v1.TripStop;
import pl.krktransit.schedule.domain.lineroute.LineDirection;

final class GrpcResponseMapper {
    GetLineRoutesResponse lineRoutes(List<LineDirection> directions) {
        return GetLineRoutesResponse.newBuilder()
                .addAllDirections(directions.stream().map(this::direction).toList()).build();
    }

    private pl.krktransit.schedule.contracts.v1.LineDirection direction(LineDirection source) {
        return pl.krktransit.schedule.contracts.v1.LineDirection.newBuilder()
                .setHeadsign(source.headsign()).setTripsCount(source.tripsCount())
                .addAllStops(source.stops().stream().map(this::routeStop).toList())
                .addAllSegments(source.segments().stream().map(this::routeSegment).toList()).build();
    }

    private RouteStop routeStop(pl.krktransit.schedule.domain.lineroute.RouteStop stop) {
        return RouteStop.newBuilder().setStopKey(stop.key().value())
                .setStopName(stop.stopName()).setStopDesc(stop.stopDesc())
                .setLatitude(stop.latitude()).setLongitude(stop.longitude()).build();
    }

    private RouteSegment routeSegment(pl.krktransit.schedule.domain.lineroute.RouteSegment segment) {
        return RouteSegment.newBuilder()
                .setFromStopKey(segment.key().from().value()).setToStopKey(segment.key().to().value())
                .addAllPoints(segment.points().stream().map(point -> RoutePoint.newBuilder()
                        .setLatitude(point.latitude()).setLongitude(point.longitude()).build()).toList()).build();
    }

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
