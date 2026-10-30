package pl.krktransit.publicapi.adapter.outbound.grpc;

import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.ShapePoint;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummary;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;

final class ScheduleProtoMapper {
    GetCurrentHashRequest currentHashRequest(FeedId feed) {
        return GetCurrentHashRequest.newBuilder().setFeedId(pl.krktransit.schedule.contracts.v1.FeedId.newBuilder()
                .setCityId(feed.cityId()).setSourceId(feed.sourceId())).build();
    }

    GetTripContextRequest tripRequest(String hash, String tripId) {
        return GetTripContextRequest.newBuilder().setHash(hash).setTripId(tripId).build();
    }

    GetShapeRequest shapeRequest(String hash, String shapeId) {
        return GetShapeRequest.newBuilder().setHash(hash).setShapeId(shapeId).build();
    }

    TripSummary summary(GetTripContextResponse trip) {
        return new TripSummary(trip.getLineNumber(), trip.hasHeadsign() ? trip.getHeadsign() : null,
                trip.hasShapeId() ? trip.getShapeId() : null);
    }

    TripStops tripStops(GetTripContextResponse trip) {
        return new TripStops(trip.getTripId(), trip.getStopsList().stream().map(this::stop).toList());
    }

    Shape shape(GetShapeResponse shape) {
        return new Shape(shape.getShapeId(), shape.getPointsList().stream().map(this::point).toList());
    }

    private TripStop stop(pl.krktransit.schedule.contracts.v1.TripStop stop) {
        return new TripStop(stop.getStopId(), stop.getStopName(), stop.hasStopDesc() ? stop.getStopDesc() : null,
                stop.hasLatitude() ? stop.getLatitude() : null, stop.hasLongitude() ? stop.getLongitude() : null,
                stop.getStopSequence(), stop.getArrivalSeconds(), stop.getDepartureSeconds());
    }

    private ShapePoint point(pl.krktransit.schedule.contracts.v1.ShapePoint point) {
        return new ShapePoint(point.getLatitude(), point.getLongitude(), point.getSequence());
    }
}
