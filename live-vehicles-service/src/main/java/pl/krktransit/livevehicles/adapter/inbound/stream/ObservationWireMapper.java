package pl.krktransit.livevehicles.adapter.inbound.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;

public final class ObservationWireMapper {
    private static final int INCOMING_AT = 0;
    private static final int STOPPED_AT = 1;
    private static final int IN_TRANSIT_TO = 2;

    public Optional<VehiclePosition> decode(SourceId source, String streamId, byte[] payload)
            throws InvalidProtocolBufferException {
        var observation = parse(payload);
        validateSource(source, observation);
        if (!observation.hasVehiclePosition()) return Optional.empty();
        if (!eligible(observation)) return Optional.empty();
        return Optional.of(position(source, streamId, observation));
    }

    private Observation parse(byte[] payload) throws InvalidProtocolBufferException {
        if (payload == null) throw new IllegalArgumentException("Observation payload is missing");
        return Observation.parseFrom(payload);
    }

    private VehiclePosition position(SourceId source, String streamId, Observation observation) {
        try {
            var fetchedAt = fetchedAt(observation);
            var vehicle = observation.getVehiclePosition();
            var location = vehicle.getPosition();
            return new VehiclePosition(source, tripId(source, vehicle),
                    text(vehicle.hasVehicleId(), vehicle.getVehicleId()),
                    text(vehicle.hasLicensePlate(), vehicle.getLicensePlate()), location.getLatitude(), location.getLongitude(),
                    bearing(location), providerTime(vehicle.hasTimestamp(), vehicle.getTimestamp()), fetchedAt, StreamId.parse(streamId),
                    progress(source, vehicle));
        } catch (DateTimeException failure) {
            throw new IllegalArgumentException("Invalid observation timestamp", failure);
        }
    }

    private Instant fetchedAt(Observation observation) {
        var fetched = observation.getFetchedAt();
        if (!validTimestamp(fetched.getSeconds(), fetched.getNanos())) {
            throw new IllegalArgumentException("Invalid observation timestamp");
        }
        return Instant.ofEpochSecond(fetched.getSeconds(), fetched.getNanos());
    }

    private void validateSource(SourceId expected, Observation observation) {
        if (!matchesSource(expected, observation)) {
            throw new IllegalArgumentException("Observation source does not match its stream");
        }
    }

    private boolean matchesSource(SourceId expected, Observation observation) {
        return observation.hasSource() && expected.cityId().equals(observation.getSource().getCityId())
                && expected.sourceId().equals(observation.getSource().getSourceId());
    }

    private boolean eligible(Observation observation) {
        var vehicle = observation.getVehiclePosition();
        return observation.hasFetchedAt() && hasCoordinates(vehicle) && hasVehicleIdentity(vehicle);
    }

    private boolean hasCoordinates(pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        return vehicle.hasPosition() && vehicle.getPosition().hasLatitude() && vehicle.getPosition().hasLongitude();
    }

    private boolean hasVehicleIdentity(pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        return hasText(vehicle.hasLicensePlate(), vehicle.getLicensePlate())
                || hasText(vehicle.hasVehicleId(), vehicle.getVehicleId());
    }

    private String tripId(SourceId source, pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        var original = text(vehicle.hasTripId(), vehicle.getTripId());
        return original == null ? null : source.scope(original);
    }

    private StopProgress progress(SourceId source, pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        return new StopProgress(stopId(source, vehicle), sequence(vehicle), status(vehicle));
    }

    private String stopId(SourceId source, pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        var original = text(vehicle.hasStopId(), vehicle.getStopId());
        return original == null ? null : source.scope(original);
    }

    private Integer sequence(pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        if (!hasRepresentableSequence(vehicle)) return null;
        return vehicle.getCurrentStopSequence();
    }

    private boolean hasRepresentableSequence(pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        return vehicle.hasCurrentStopSequence() && vehicle.getCurrentStopSequence() >= 0;
    }

    private VehicleStopStatus status(pl.krktransit.realtime.contracts.v1.VehiclePosition vehicle) {
        if (!vehicle.hasCurrentStatus()) return null;
        return switch (vehicle.getCurrentStatus()) {
            case INCOMING_AT -> VehicleStopStatus.INCOMING_AT;
            case STOPPED_AT -> VehicleStopStatus.STOPPED_AT;
            case IN_TRANSIT_TO -> VehicleStopStatus.IN_TRANSIT_TO;
            default -> VehicleStopStatus.UNRECOGNIZED;
        };
    }

    private Double bearing(Position position) {
        if (!hasValidBearing(position)) return null;
        return (double) position.getBearing();
    }

    private boolean hasValidBearing(Position position) {
        return position.hasBearing() && VehiclePosition.validBearing(position.getBearing());
    }

    private Instant providerTime(boolean present, long seconds) {
        if (!validProviderTime(present, seconds)) return null;
        return Instant.ofEpochSecond(seconds);
    }

    private boolean validProviderTime(boolean present, long seconds) {
        return present && seconds > 0 && validTimestamp(seconds, 0);
    }

    private String text(boolean present, String value) { return hasText(present, value) ? value : null; }

    private boolean hasText(boolean present, String value) { return present && !value.isBlank(); }

    private boolean validTimestamp(long seconds, int nanos) {
        return seconds >= -62135596800L && seconds <= 253402300799L && nanos >= 0 && nanos <= 999999999;
    }
}
