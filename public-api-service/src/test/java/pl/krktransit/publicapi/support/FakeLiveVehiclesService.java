package pl.krktransit.publicapi.support;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastRequest;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastResponse;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;

public final class FakeLiveVehiclesService extends LiveVehiclesServiceGrpc.LiveVehiclesServiceImplBase {
    public final AtomicInteger calls = new AtomicInteger();
    public volatile ListVehiclePositionsResponse positions = ListVehiclePositionsResponse.getDefaultInstance();
    public volatile Status failure;
    public final List<String> forecastRequests = new CopyOnWriteArrayList<>();
    public volatile GetTripForecastResponse forecast = GetTripForecastResponse.getDefaultInstance();
    public volatile Status forecastFailure;

    public void reset() {
        calls.set(0);
        positions = ListVehiclePositionsResponse.getDefaultInstance();
        failure = null;
        forecastRequests.clear();
        forecast = GetTripForecastResponse.getDefaultInstance();
        forecastFailure = null;
    }

    @Override
    public void getTripForecast(GetTripForecastRequest request, StreamObserver<GetTripForecastResponse> observer) {
        forecastRequests.add(request.getTripId());
        if (forecastFailure != null) {
            observer.onError(forecastFailure.asRuntimeException());
            return;
        }
        observer.onNext(forecast);
        observer.onCompleted();
    }

    @Override
    public void listVehiclePositions(ListVehiclePositionsRequest request,
                                     StreamObserver<ListVehiclePositionsResponse> observer) {
        calls.incrementAndGet();
        if (failure != null) {
            observer.onError(failure.asRuntimeException());
            return;
        }
        observer.onNext(positions);
        observer.onCompleted();
    }
}
