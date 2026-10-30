package pl.krktransit.publicapi.support;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicInteger;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;

public final class FakeLiveVehiclesService extends LiveVehiclesServiceGrpc.LiveVehiclesServiceImplBase {
    public final AtomicInteger calls = new AtomicInteger();
    public volatile ListVehiclePositionsResponse positions = ListVehiclePositionsResponse.getDefaultInstance();
    public volatile Status failure;

    public void reset() {
        calls.set(0);
        positions = ListVehiclePositionsResponse.getDefaultInstance();
        failure = null;
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
