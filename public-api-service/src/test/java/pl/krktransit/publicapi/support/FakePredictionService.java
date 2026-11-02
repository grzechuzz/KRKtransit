package pl.krktransit.publicapi.support;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pl.krktransit.prediction.contracts.v1.PredictArrivalsRequest;
import pl.krktransit.prediction.contracts.v1.PredictArrivalsResponse;
import pl.krktransit.prediction.contracts.v1.PredictionServiceGrpc;

public final class FakePredictionService extends PredictionServiceGrpc.PredictionServiceImplBase {
    public final List<PredictArrivalsRequest> requests = new CopyOnWriteArrayList<>();
    public volatile PredictArrivalsResponse response = PredictArrivalsResponse.getDefaultInstance();
    public volatile Status failure;
    public volatile Duration delay = Duration.ZERO;

    public void reset() {
        requests.clear();
        response = PredictArrivalsResponse.getDefaultInstance();
        failure = null;
        delay = Duration.ZERO;
    }

    @Override
    public void predictArrivals(PredictArrivalsRequest request, StreamObserver<PredictArrivalsResponse> observer) {
        requests.add(request);
        pause();
        if (failure != null) {
            observer.onError(failure.asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }

    private void pause() {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
