package pl.krktransit.stopevents.adapter.outbound.redis;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import pl.krktransit.stopevents.adapter.outbound.redis.proto.DetectorStateProto.ForecastSnapshot;
import pl.krktransit.stopevents.adapter.outbound.redis.proto.DetectorStateProto.PinSnapshot;
import pl.krktransit.stopevents.adapter.outbound.redis.proto.DetectorStateProto.SavedSnapshot;
import pl.krktransit.stopevents.adapter.outbound.redis.proto.DetectorStateProto.TripSnapshot;
import pl.krktransit.stopevents.adapter.outbound.redis.proto.DetectorStateProto.VehicleSnapshot;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.domain.state.RetainedForecast;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleState;

final class StateCodec {
    byte[] vehicle(VehicleState state) {
        return VehicleSnapshot.newBuilder().setTripId(state.tripId()).setLicensePlate(state.licensePlate())
                .setSequence(state.sequence()).setTimestamp(timestamp(state.timestamp()))
                .setFetchedAt(timestamp(state.fetchedAt())).build().toByteArray();
    }

    VehicleState vehicle(byte[] bytes) {
        if (bytes == null) return null;
        try {
            var value = VehicleSnapshot.parseFrom(bytes);
            require(value.hasTimestamp() && value.hasFetchedAt() && !value.getTripId().isBlank());
            return new VehicleState(value.getTripId(), value.getLicensePlate(), value.getSequence(),
                    instant(value.getTimestamp()), instant(value.getFetchedAt()));
        } catch (InvalidProtocolBufferException failure) { throw invalid(failure); }
    }

    byte[] pin(TripPin pin) {
        return PinSnapshot.newBuilder().setHash(pin.hash()).setFetchedAt(timestamp(pin.fetchedAt())).build().toByteArray();
    }

    TripPin pin(byte[] bytes) {
        if (bytes == null) return null;
        try {
            var value = PinSnapshot.parseFrom(bytes);
            require(value.getHash().matches("[0-9a-f]{64}") && value.hasFetchedAt());
            return new TripPin(value.getHash(), instant(value.getFetchedAt()));
        } catch (InvalidProtocolBufferException failure) { throw invalid(failure); }
    }

    byte[] forecasts(TripForecasts state) {
        var value = TripSnapshot.newBuilder().setFetchedAt(timestamp(state.fetchedAt()));
        if (state.lastMinSequence() != null) value.setLastMinSequence(state.lastMinSequence());
        state.stops().forEach((sequence, stop) -> value.putStops(sequence, ForecastSnapshot.newBuilder()
                .setStopId(stop.stopId()).setFirstSeen(timestamp(stop.firstSeenArrival()))
                .setLastSeen(timestamp(stop.lastSeenArrival())).build()));
        return value.build().toByteArray();
    }

    TripForecasts forecasts(byte[] bytes) {
        if (bytes == null) return null;
        try {
            var value = TripSnapshot.parseFrom(bytes);
            require(value.hasFetchedAt());
            var stops = new HashMap<Integer, RetainedForecast>();
            value.getStopsMap().forEach((sequence, stop) -> {
                require(stop.hasFirstSeen() && stop.hasLastSeen());
                stops.put(sequence, new RetainedForecast(stop.getStopId(), instant(stop.getFirstSeen()), instant(stop.getLastSeen())));
            });
            return new TripForecasts(stops, value.hasLastMinSequence() ? value.getLastMinSequence() : null,
                    instant(value.getFetchedAt()));
        } catch (InvalidProtocolBufferException failure) { throw invalid(failure); }
    }

    byte[] saved(SavedStop stop) {
        return SavedSnapshot.newBuilder().setDelaySeconds(stop.delaySeconds()).setEventTime(timestamp(stop.eventTime()))
                .build().toByteArray();
    }

    SavedStop saved(byte[] bytes) {
        try {
            var value = SavedSnapshot.parseFrom(bytes);
            require(value.hasEventTime());
            return new SavedStop(value.getDelaySeconds(), instant(value.getEventTime()));
        } catch (InvalidProtocolBufferException failure) { throw invalid(failure); }
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }

    private Instant instant(Timestamp value) {
        require(value.getNanos() >= 0 && value.getNanos() < 1_000_000_000);
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private void require(boolean valid) {
        if (!valid) throw new StateIntegrityFailure("Incomplete detector state; manual repair is required");
    }

    private StateIntegrityFailure invalid(InvalidProtocolBufferException failure) {
        return new StateIntegrityFailure("Malformed detector state; manual repair is required", failure);
    }
}
