package pl.krktransit.publicapi.support;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import pl.krktransit.stopevents.contracts.v1.MaxDelayResponse;
import pl.krktransit.stopevents.contracts.v1.PunctualityResponse;
import pl.krktransit.stopevents.contracts.v1.RouteDelayResponse;
import pl.krktransit.stopevents.contracts.v1.StatisticsRequest;
import pl.krktransit.stopevents.contracts.v1.StatisticsServiceGrpc;
import pl.krktransit.stopevents.contracts.v1.TrendResponse;

public final class FakeStatisticsService extends StatisticsServiceGrpc.StatisticsServiceImplBase {
    public volatile MaxDelayResponse maxDelay = MaxDelayResponse.getDefaultInstance();
    public volatile RouteDelayResponse routeDelay = RouteDelayResponse.getDefaultInstance();
    public volatile PunctualityResponse punctuality = PunctualityResponse.getDefaultInstance();
    public volatile TrendResponse trend = TrendResponse.getDefaultInstance();
    public volatile Status failure;
    public volatile StatisticsRequest lastRequest;

    public void reset() {
        maxDelay = MaxDelayResponse.getDefaultInstance();
        routeDelay = RouteDelayResponse.getDefaultInstance();
        punctuality = PunctualityResponse.getDefaultInstance();
        trend = TrendResponse.getDefaultInstance();
        failure = null;
        lastRequest = null;
    }

    @Override
    public void getMaxDelay(StatisticsRequest request, StreamObserver<MaxDelayResponse> observer) {
        respond(request, maxDelay, observer);
    }

    @Override
    public void getRouteDelay(StatisticsRequest request, StreamObserver<RouteDelayResponse> observer) {
        respond(request, routeDelay, observer);
    }

    @Override
    public void getPunctuality(StatisticsRequest request, StreamObserver<PunctualityResponse> observer) {
        respond(request, punctuality, observer);
    }

    @Override
    public void getTrend(StatisticsRequest request, StreamObserver<TrendResponse> observer) {
        respond(request, trend, observer);
    }

    private <T> void respond(StatisticsRequest request, T response, StreamObserver<T> observer) {
        lastRequest = request;
        if (failure != null) {
            observer.onError(failure.asRuntimeException());
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }
}
