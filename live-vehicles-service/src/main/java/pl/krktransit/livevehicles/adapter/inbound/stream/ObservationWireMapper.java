package pl.krktransit.livevehicles.adapter.inbound.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import pl.krktransit.livevehicles.domain.LiveObservation;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopForecast;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.TripForecast;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;
import pl.krktransit.realtime.contracts.v1.StopTimeUpdate;
import pl.krktransit.realtime.contracts.v1.TripUpdate;

public final class ObservationWireMapper {
    private static final int INCOMING_AT = 0;
    private static final int STOPPED_AT = 1;
    private static final int IN_TRANSIT_TO = 2;

    public Optional<LiveObservation> decode(SourceId source, String streamId, byte[] payload)
            throws InvalidProtocolBufferException {
        var observation = parse(payload);
        validateSource(source, observation);
        return switch (observation.getPayloadCase()) {
            case VEHICLE_POSITION -> vehiclePosition(source, streamId, observation);
            case TRIP_UPDATE -> tripForecast(source, observation);
            case PAYLOAD_NOT_SET -> Optional.empty();
        };
    }

    private Optional<LiveObservation> vehiclePosition(SourceId source, String streamId, Observation observation) {
        if (!eligible(observation)) return Optional.empty();
        return Optional.of(position(source, streamId, observation));
    }

    private Optional<LiveObservation> tripForecast(SourceId source, Observation observation) {
        var update = observation.getTripUpdate();
        if (!forecastEligible(observation, update)) return Optional.empty();
        return Optional.of(new TripForecast(source.scope(update.getTripId()), fetchedAt(observation), stops(source, update)));
    }

    private boolean forecastEligible(Observation observation, TripUpdate update) {
        return observation.hasFetchedAt() && hasText(update.hasTripId(), update.getTripId());
    }

    private List<StopForecast> stops(SourceId source, TripUpdate update) {
        return update.getStopTimeUpdatesList().stream().filter(this::usableForecast).map(stop -> stop(source, stop)).toList();
    }

    private boolean usableForecast(StopTimeUpdate stop) { return hasArrivalTime(stop) && hasStopIdentity(stop); }

    private boolean hasArrivalTime(StopTimeUpdate stop) {
        return stop.hasArrival() && validProviderTime(stop.getArrival().hasTime(), stop.getArrival().getTime());
    }

    private boolean hasStopIdentity(StopTimeUpdate stop) {
        return sequence(stop.hasStopSequence(), stop.getStopSequence()) != null || hasText(stop.hasStopId(), stop.getStopId());
    }

    private StopForecast stop(SourceId source, StopTimeUpdate stop) {
        return new StopForecast(sequence(stop.hasStopSequence(), stop.getStopSequence()),
                scoped(source, stop.hasStopId(), stop.getStopId()), Instant.ofEpochSecond(stop.getArrival().getTime()));
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
        return new StopProgress(scoped(source, vehicle.hasStopId(), vehicle.getStopId()),
                sequence(vehicle.hasCurrentStopSequence(), vehicle.getCurrentStopSequence()), status(vehicle));
    }

    private String scoped(SourceId source, boolean present, String value) {
        var original = text(present, value);
        return original == null ? null : source.scope(original);
    }

    private Integer sequence(boolean present, int value) { return present && value >= 0 ? value : null; }

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
