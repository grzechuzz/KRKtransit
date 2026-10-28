package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

import static pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt.SourceValues.isCoordinate;
import static pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt.SourceValues.posixSeconds;
import static pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt.SourceValues.text;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import java.util.List;
import java.util.Optional;
import pl.krktransit.realtime.ingestion.application.collection.DecodeFailure;
import pl.krktransit.realtime.ingestion.application.collection.DecodedFeed;
import pl.krktransit.realtime.ingestion.application.collection.FetchedFeed;
import pl.krktransit.realtime.ingestion.application.port.FeedDecoder;
import pl.krktransit.realtime.ingestion.domain.observation.ObservationOrigin;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleLocation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;

public final class GtfsRealtimeDecoder implements FeedDecoder {
    private static final int VEHICLE_CURRENT_STATUS_FIELD = 4;
    private static final int TRIP_SCHEDULE_RELATIONSHIP_FIELD = 4;
    private static final int STOP_SCHEDULE_RELATIONSHIP_FIELD = 5;

    @Override
    public DecodedFeed decode(FetchedFeed feed) {
        FeedMessage message = parse(feed.body());
        var origin = new ObservationOrigin(feed.endpoint().source(), feed.fetchedAt(), feedTimestamp(message));
        var entities = new DecodedEntities();
        message.getEntityList().forEach(entity -> decodeEntity(feed, origin, entity).ifPresent(entities::add));
        return entities.result();
    }

    private static FeedMessage parse(byte[] body) {
        FeedMessage message;
        try {
            message = FeedMessage.parser().parsePartialFrom(body);
        } catch (InvalidProtocolBufferException failure) {
            throw new DecodeFailure("Malformed GTFS-Realtime protobuf: " + failure.getMessage(), failure);
        }
        if (!hasValidHeader(message)) {
            throw new DecodeFailure("GTFS-Realtime feed header is missing");
        }
        return message;
    }

    private static boolean hasValidHeader(FeedMessage message) {
        return message.hasHeader() && message.getHeader().hasGtfsRealtimeVersion();
    }

    private static Long feedTimestamp(FeedMessage message) {
        var header = message.getHeader();
        return posixSeconds(header.hasTimestamp(), header.getTimestamp());
    }

    private static Optional<RealtimeObservation> decodeEntity(FetchedFeed feed, ObservationOrigin origin,
                                                              FeedEntity entity) {
        return switch (feed.endpoint().kind()) {
            case VEHICLE_POSITIONS -> entity.hasVehicle()
                    ? Optional.of(vehicle(origin, entity.getVehicle())) : Optional.empty();
            case TRIP_UPDATES -> entity.hasTripUpdate()
                    ? Optional.of(tripUpdate(origin, entity.getTripUpdate())) : Optional.empty();
        };
    }

    private static VehicleObservation vehicle(ObservationOrigin origin, GtfsRealtime.VehiclePosition vehicle) {
        var descriptor = vehicle.getVehicle();
        return new VehicleObservation(origin,
                text(vehicle.getTrip().hasTripId(), vehicle.getTrip().getTripId()),
                text(descriptor.hasId(), descriptor.getId()),
                text(descriptor.hasLicensePlate(), descriptor.getLicensePlate()),
                location(vehicle),
                text(vehicle.hasStopId(), vehicle.getStopId()),
                currentStopSequence(vehicle),
                currentStatus(vehicle),
                posixSeconds(vehicle.hasTimestamp(), vehicle.getTimestamp()));
    }

    private static VehicleLocation location(GtfsRealtime.VehiclePosition vehicle) {
        if (!vehicle.hasPosition()) {
            return null;
        }
        var position = vehicle.getPosition();
        if (!hasValidCoordinates(position)) {
            return null;
        }
        return new VehicleLocation(position.getLatitude(), position.getLongitude(), bearing(position));
    }

    private static Integer currentStopSequence(GtfsRealtime.VehiclePosition vehicle) {
        return vehicle.hasCurrentStopSequence() ? vehicle.getCurrentStopSequence() : null;
    }

    private static boolean hasValidCoordinates(GtfsRealtime.Position position) {
        return position.hasLatitude() && position.hasLongitude()
                && isCoordinate(position.getLatitude(), 90) && isCoordinate(position.getLongitude(), 180);
    }

    private static Float bearing(GtfsRealtime.Position position) {
        return hasValidBearing(position) ? position.getBearing() : null;
    }

    private static boolean hasValidBearing(GtfsRealtime.Position position) {
        return position.hasBearing() && Float.isFinite(position.getBearing());
    }

    private static Integer currentStatus(GtfsRealtime.VehiclePosition vehicle) {
        return RawEnumCode.of(vehicle.hasCurrentStatus(), vehicle.getCurrentStatus().getNumber(),
                vehicle.getUnknownFields(), VEHICLE_CURRENT_STATUS_FIELD);
    }

    private static TripUpdateObservation tripUpdate(ObservationOrigin origin, GtfsRealtime.TripUpdate update) {
        var trip = update.getTrip();
        return new TripUpdateObservation(origin,
                text(trip.hasTripId(), trip.getTripId()),
                tripScheduleRelationship(trip),
                text(update.getVehicle().hasId(), update.getVehicle().getId()),
                posixSeconds(update.hasTimestamp(), update.getTimestamp()),
                usableStopUpdates(update.getStopTimeUpdateList()));
    }

    private static Integer tripScheduleRelationship(TripDescriptor trip) {
        return RawEnumCode.of(trip.hasScheduleRelationship(), trip.getScheduleRelationship().getNumber(),
                trip.getUnknownFields(), TRIP_SCHEDULE_RELATIONSHIP_FIELD);
    }

    private static List<StopUpdate> usableStopUpdates(List<StopTimeUpdate> updates) {
        return updates.stream().map(GtfsRealtimeDecoder::stopUpdate).filter(EntityFilter::isUsable).toList();
    }

    private static StopUpdate stopUpdate(StopTimeUpdate update) {
        return new StopUpdate(
                stopSequence(update),
                text(update.hasStopId(), update.getStopId()),
                arrival(update),
                departure(update),
                stopScheduleRelationship(update));
    }

    private static Integer stopSequence(StopTimeUpdate update) {
        return update.hasStopSequence() ? update.getStopSequence() : null;
    }

    private static StopEvent arrival(StopTimeUpdate update) {
        return update.hasArrival() ? estimate(update.getArrival()) : null;
    }

    private static StopEvent departure(StopTimeUpdate update) {
        return update.hasDeparture() ? estimate(update.getDeparture()) : null;
    }

    private static Integer stopScheduleRelationship(StopTimeUpdate update) {
        return RawEnumCode.of(update.hasScheduleRelationship(), update.getScheduleRelationship().getNumber(),
                update.getUnknownFields(), STOP_SCHEDULE_RELATIONSHIP_FIELD);
    }

    private static StopEvent estimate(StopTimeEvent event) {
        Long time = posixSeconds(event.hasTime(), event.getTime());
        Integer delay = event.hasDelay() ? event.getDelay() : null;
        return hasEstimate(time, delay) ? new StopEvent(time, delay) : null;
    }

    private static boolean hasEstimate(Long time, Integer delay) {
        return time != null || delay != null;
    }
}
