package pl.krktransit.livevehicles.adapter.inbound.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import pl.krktransit.livevehicles.application.LiveVehicleQueries;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastRequest;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastResponse;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.contracts.v1.StopForecast;
import pl.krktransit.livevehicles.contracts.v1.VehiclePosition;
import pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.TripForecast;

public final class LiveVehiclesGrpcEndpoint extends LiveVehiclesServiceGrpc.LiveVehiclesServiceImplBase {
    private final LiveVehicleQueries queries;

    public LiveVehiclesGrpcEndpoint(LiveVehicleQueries queries) { this.queries = queries; }

    @Override
    public void listVehiclePositions(ListVehiclePositionsRequest request, StreamObserver<ListVehiclePositionsResponse> observer) {
        var snapshot = queries.list();
        var response = ListVehiclePositionsResponse.newBuilder().setGeneratedAt(timestamp(snapshot.generatedAt()));
        snapshot.vehicles().forEach(vehicle -> response.addVehicles(position(vehicle)));
        observer.onNext(response.build());
        observer.onCompleted();
    }

    @Override
    public void getTripForecast(GetTripForecastRequest request, StreamObserver<GetTripForecastResponse> observer) {
        observer.onNext(queries.forecast(request.getTripId()).map(this::forecast)
                .orElseGet(GetTripForecastResponse::getDefaultInstance));
        observer.onCompleted();
    }

    private GetTripForecastResponse forecast(TripForecast forecast) {
        var response = GetTripForecastResponse.newBuilder().setFetchedAt(timestamp(forecast.fetchedAt()));
        forecast.stops().forEach(stop -> response.addStops(stop(stop)));
        return response.build();
    }

    private StopForecast stop(pl.krktransit.livevehicles.domain.StopForecast stop) {
        var response = StopForecast.newBuilder().setArrivalTime(timestamp(stop.arrivalTime()));
        if (stop.stopSequence() != null) response.setStopSequence(stop.stopSequence());
        if (stop.stopId() != null) response.setStopId(stop.stopId());
        return response.build();
    }

    private VehiclePosition position(pl.krktransit.livevehicles.domain.VehiclePosition position) {
        var response = VehiclePosition.newBuilder().setCityId(position.source().cityId())
                .setSourceId(position.source().sourceId());
        addIdentity(response, position);
        addLocation(response, position);
        addTimes(response, position);
        addProgress(response, position.progress());
        return response.build();
    }

    private void addIdentity(VehiclePosition.Builder response, pl.krktransit.livevehicles.domain.VehiclePosition position) {
        if (position.tripId() != null) response.setTripId(position.tripId());
        if (position.vehicleId() != null) response.setVehicleId(position.vehicleId());
        if (position.licensePlate() != null) response.setLicensePlate(position.licensePlate());
    }

    private void addLocation(VehiclePosition.Builder response, pl.krktransit.livevehicles.domain.VehiclePosition position) {
        response.setLatitude(position.latitude()).setLongitude(position.longitude());
        if (position.bearing() != null) response.setBearing(position.bearing());
    }

    private void addTimes(VehiclePosition.Builder response, pl.krktransit.livevehicles.domain.VehiclePosition position) {
        response.setFetchedAt(timestamp(position.fetchedAt()));
        if (position.timestamp() != null) response.setTimestamp(timestamp(position.timestamp()));
    }

    private void addProgress(VehiclePosition.Builder response, StopProgress progress) {
        if (progress.stopId() != null) response.setStopId(progress.stopId());
        if (progress.sequence() != null) response.setCurrentStopSequence(progress.sequence());
        if (progress.status() != null) response.setCurrentStatus(status(progress.status()));
    }

    private VehicleStopStatus status(pl.krktransit.livevehicles.domain.VehicleStopStatus status) {
        return switch (status) {
            case INCOMING_AT -> VehicleStopStatus.VEHICLE_STOP_STATUS_INCOMING_AT;
            case STOPPED_AT -> VehicleStopStatus.VEHICLE_STOP_STATUS_STOPPED_AT;
            case IN_TRANSIT_TO -> VehicleStopStatus.VEHICLE_STOP_STATUS_IN_TRANSIT_TO;
            case UNRECOGNIZED -> VehicleStopStatus.VEHICLE_STOP_STATUS_UNRECOGNIZED;
        };
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
