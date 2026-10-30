package pl.krktransit.publicapi.adapter.outbound.grpc;

import static pl.krktransit.publicapi.adapter.outbound.grpc.ProtoTimes.instant;

import java.util.List;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.vehicles.StopProgress;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;

final class VehicleProtoMapper {
    List<VehiclePosition> positions(ListVehiclePositionsResponse response) {
        return response.getVehiclesList().stream().filter(this::isDisplayable).map(this::position).toList();
    }

    private boolean isDisplayable(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return hasIdentity(vehicle) && hasCoordinates(vehicle) && vehicle.hasTimestamp();
    }

    private boolean hasIdentity(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return hasText(vehicle.hasCityId(), vehicle.getCityId()) && hasText(vehicle.hasSourceId(), vehicle.getSourceId())
                && hasText(vehicle.hasTripId(), vehicle.getTripId())
                && hasText(vehicle.hasLicensePlate(), vehicle.getLicensePlate());
    }

    private boolean hasCoordinates(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return vehicle.hasLatitude() && vehicle.hasLongitude();
    }

    private VehiclePosition position(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return new VehiclePosition(new FeedId(vehicle.getCityId(), vehicle.getSourceId()), vehicle.getTripId(),
                vehicle.getLicensePlate(), vehicle.getLatitude(), vehicle.getLongitude(),
                vehicle.hasBearing() ? vehicle.getBearing() : null, instant(vehicle.getTimestamp()), progress(vehicle));
    }

    private StopProgress progress(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return new StopProgress(hasText(vehicle.hasStopId(), vehicle.getStopId()) ? vehicle.getStopId() : null,
                sequence(vehicle), vehicle.hasCurrentStatus() ? status(vehicle.getCurrentStatus()) : null);
    }

    private Integer sequence(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        if (!hasRepresentableSequence(vehicle)) return null;
        return vehicle.getCurrentStopSequence();
    }

    private boolean hasRepresentableSequence(pl.krktransit.livevehicles.contracts.v1.VehiclePosition vehicle) {
        return vehicle.hasCurrentStopSequence() && vehicle.getCurrentStopSequence() >= 0;
    }

    private pl.krktransit.publicapi.application.vehicles.VehicleStopStatus status(VehicleStopStatus status) {
        return switch (status) {
            case VEHICLE_STOP_STATUS_UNSPECIFIED -> null;
            case VEHICLE_STOP_STATUS_INCOMING_AT -> pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.INCOMING_AT;
            case VEHICLE_STOP_STATUS_STOPPED_AT -> pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.STOPPED_AT;
            case VEHICLE_STOP_STATUS_IN_TRANSIT_TO -> pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.IN_TRANSIT_TO;
            case VEHICLE_STOP_STATUS_UNRECOGNIZED, UNRECOGNIZED ->
                    pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.UNRECOGNIZED;
        };
    }

    private static boolean hasText(boolean present, String value) { return present && !value.isBlank(); }
}
