package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;
import pl.krktransit.realtime.contracts.v1.Source;
import pl.krktransit.realtime.contracts.v1.StopTimeEvent;
import pl.krktransit.realtime.contracts.v1.StopTimeUpdate;
import pl.krktransit.realtime.contracts.v1.TripUpdate;
import pl.krktransit.realtime.contracts.v1.VehiclePosition;
import pl.krktransit.realtime.ingestion.domain.observation.ObservationOrigin;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleLocation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;

final class ObservationWireMapper {
    byte[] encode(RealtimeObservation observation) {
        return toWire(observation).toByteArray();
    }

    Observation toWire(RealtimeObservation observation) {
        var wire = origin(observation.origin());
        switch (observation) {
            case VehicleObservation vehicle -> wire.setVehiclePosition(vehicle(vehicle));
            case TripUpdateObservation update -> wire.setTripUpdate(tripUpdate(update));
        }
        return wire.build();
    }

    private static Observation.Builder origin(ObservationOrigin origin) {
        var wire = Observation.newBuilder()
                .setSource(Source.newBuilder().setCityId(origin.source().cityId()).setSourceId(origin.source().sourceId()))
                .setFetchedAt(timestamp(origin.fetchedAt()));
        setIfPresent(origin.feedTimestamp(), wire::setFeedTimestamp);
        return wire;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static VehiclePosition vehicle(VehicleObservation vehicle) {
        var wire = VehiclePosition.newBuilder();
        setIfPresent(vehicle.tripId(), wire::setTripId);
        setIfPresent(vehicle.vehicleId(), wire::setVehicleId);
        setIfPresent(vehicle.licensePlate(), wire::setLicensePlate);
        setIfPresent(vehicle.location(), location -> wire.setPosition(position(location)));
        setIfPresent(vehicle.stopId(), wire::setStopId);
        setIfPresent(vehicle.currentStopSequence(), wire::setCurrentStopSequence);
        setIfPresent(vehicle.currentStatus(), wire::setCurrentStatus);
        setIfPresent(vehicle.timestamp(), wire::setTimestamp);
        return wire.build();
    }

    private static Position position(VehicleLocation location) {
        var wire = Position.newBuilder();
        setIfPresent(location.latitude(), wire::setLatitude);
        setIfPresent(location.longitude(), wire::setLongitude);
        setIfPresent(location.bearing(), wire::setBearing);
        return wire.build();
    }

    private static TripUpdate tripUpdate(TripUpdateObservation update) {
        var wire = TripUpdate.newBuilder();
        setIfPresent(update.tripId(), wire::setTripId);
        setIfPresent(update.tripScheduleRelationship(), wire::setTripScheduleRelationship);
        setIfPresent(update.vehicleId(), wire::setVehicleId);
        setIfPresent(update.timestamp(), wire::setTimestamp);
        update.stopUpdates().forEach(stop -> wire.addStopTimeUpdates(stopUpdate(stop)));
        return wire.build();
    }

    private static StopTimeUpdate stopUpdate(StopUpdate stop) {
        var wire = StopTimeUpdate.newBuilder();
        setIfPresent(stop.stopSequence(), wire::setStopSequence);
        setIfPresent(stop.stopId(), wire::setStopId);
        setIfPresent(stop.arrival(), arrival -> wire.setArrival(stopEvent(arrival)));
        setIfPresent(stop.departure(), departure -> wire.setDeparture(stopEvent(departure)));
        setIfPresent(stop.scheduleRelationship(), wire::setScheduleRelationship);
        return wire.build();
    }

    private static StopTimeEvent stopEvent(StopEvent event) {
        var wire = StopTimeEvent.newBuilder();
        setIfPresent(event.time(), wire::setTime);
        setIfPresent(event.delay(), wire::setDelay);
        return wire.build();
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
