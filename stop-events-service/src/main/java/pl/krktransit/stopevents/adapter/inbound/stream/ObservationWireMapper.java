package pl.krktransit.stopevents.adapter.inbound.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import pl.krktransit.realtime.contracts.v1.Source;
import pl.krktransit.realtime.contracts.v1.StopTimeUpdate;
import pl.krktransit.realtime.contracts.v1.TripUpdate;
import pl.krktransit.realtime.contracts.v1.VehiclePosition;
import pl.krktransit.stopevents.domain.observation.ForecastUpdate;
import pl.krktransit.stopevents.domain.observation.Observation;
import pl.krktransit.stopevents.domain.observation.StopForecast;
import pl.krktransit.stopevents.domain.observation.VehicleObservation;
import pl.krktransit.stopevents.domain.source.SourceId;

final class ObservationWireMapper {
    private static final long MIN_EPOCH_SECOND = -62135596800L;
    private static final long MAX_EPOCH_SECOND = 253402300799L;

    Observation decode(SourceId expected, byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null) throw new IllegalArgumentException("Stream entry has no payload");
        var wire = pl.krktransit.realtime.contracts.v1.Observation.parseFrom(bytes);
        requireOrigin(wire, expected);
        var fetched = instant(wire.getFetchedAt());
        return switch (wire.getPayloadCase()) {
            case VEHICLE_POSITION -> vehicle(expected, fetched, wire.getVehiclePosition());
            case TRIP_UPDATE -> forecasts(expected, fetched, wire.getTripUpdate());
            case PAYLOAD_NOT_SET -> throw new IllegalArgumentException("Observation payload is absent");
        };
    }

    private void requireOrigin(pl.krktransit.realtime.contracts.v1.Observation wire, SourceId expected) {
        if (!wire.hasSource() || !wire.hasFetchedAt()) throw new IllegalArgumentException("Missing observation origin");
        if (!matches(expected, wire.getSource())) {
            throw new IllegalArgumentException("Observation source does not match its stream");
        }
    }

    private boolean matches(SourceId expected, Source actual) {
        return expected.cityId().equals(actual.getCityId()) && expected.sourceId().equals(actual.getSourceId());
    }

    private VehicleObservation vehicle(SourceId source, Instant fetched, VehiclePosition wire) {
        var timestamp = wire.hasTimestamp() ? epoch(wire.getTimestamp()) : null;
        return new VehicleObservation(source, fetched, scope(source, wire.hasTripId(), wire.getTripId()),
                wire.hasLicensePlate() ? wire.getLicensePlate() : null, wire.hasVehicleId() ? wire.getVehicleId() : null,
                wire.hasCurrentStopSequence() ? sequence(wire.getCurrentStopSequence()) : null,
                wire.hasCurrentStatus() ? wire.getCurrentStatus() : null, timestamp);
    }

    private ForecastUpdate forecasts(SourceId source, Instant fetched, TripUpdate wire) {
        return new ForecastUpdate(source, fetched, scope(source, wire.hasTripId(), wire.getTripId()),
                wire.getStopTimeUpdatesList().stream().map(stop -> forecast(source, stop)).toList());
    }

    private StopForecast forecast(SourceId source, StopTimeUpdate wire) {
        return new StopForecast(wire.hasStopSequence() ? sequence(wire.getStopSequence()) : null,
                scope(source, wire.hasStopId(), wire.getStopId()),
                wire.hasArrival() && wire.getArrival().hasTime() ? epoch(wire.getArrival().getTime()) : null,
                wire.hasDeparture() && wire.getDeparture().hasTime() ? epoch(wire.getDeparture().getTime()) : null);
    }

    private Integer sequence(int value) {
        if (value < 0) throw new IllegalArgumentException("Stop sequence exceeds the Schedule integer range");
        return value;
    }

    private String scope(SourceId source, boolean present, String value) {
        if (!present || value.isBlank()) return null;
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Entity ID contains NUL");
        return source.scope(value);
    }

    private Instant epoch(long value) {
        if (value <= 0 || value > MAX_EPOCH_SECOND) throw new IllegalArgumentException("Invalid provider timestamp");
        return Instant.ofEpochSecond(value);
    }

    private Instant instant(Timestamp value) {
        if (!valid(value)) throw new IllegalArgumentException("Invalid fetched_at timestamp");
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private boolean valid(Timestamp value) {
        return value.getSeconds() >= MIN_EPOCH_SECOND && value.getSeconds() <= MAX_EPOCH_SECOND
                && value.getNanos() >= 0 && value.getNanos() < 1_000_000_000;
    }
}
