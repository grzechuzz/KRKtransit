package pl.krktransit.publicapi.support;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashResponse;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;

public final class FakeScheduleService extends ScheduleServiceGrpc.ScheduleServiceImplBase {
    public final Map<String, String> hashes = new ConcurrentHashMap<>();
    public final Map<String, GetTripContextResponse> trips = new ConcurrentHashMap<>();
    public final Map<String, GetShapeResponse> shapes = new ConcurrentHashMap<>();
    public final Map<String, Status> tripFailures = new ConcurrentHashMap<>();
    public final AtomicInteger tripCalls = new AtomicInteger();
    public final AtomicInteger hashCalls = new AtomicInteger();
    public final AtomicInteger maximumConcurrentTripCalls = new AtomicInteger();
    private final AtomicInteger concurrentTripCalls = new AtomicInteger();
    public volatile Duration tripDelay = Duration.ZERO;
    public volatile Status failure;

    public static String feedKey(String cityId, String sourceId) { return cityId + ":" + sourceId; }

    public static String tripKey(String hash, String tripId) { return hash + "/" + tripId; }

    public void reset() {
        hashes.clear();
        trips.clear();
        shapes.clear();
        tripFailures.clear();
        tripCalls.set(0);
        hashCalls.set(0);
        maximumConcurrentTripCalls.set(0);
        tripDelay = Duration.ZERO;
        failure = null;
    }

    @Override
    public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> observer) {
        hashCalls.incrementAndGet();
        var hash = hashes.get(feedKey(request.getFeedId().getCityId(), request.getFeedId().getSourceId()));
        respond(hash == null ? null : GetCurrentHashResponse.newBuilder().setHash(hash).build(), failure, observer);
    }

    @Override
    public void getTripContext(GetTripContextRequest request, StreamObserver<GetTripContextResponse> observer) {
        tripCalls.incrementAndGet();
        maximumConcurrentTripCalls.accumulateAndGet(concurrentTripCalls.incrementAndGet(), Math::max);
        try {
            pause();
        } finally {
            concurrentTripCalls.decrementAndGet();
        }
        var key = tripKey(request.getHash(), request.getTripId());
        respond(trips.get(key), failure == null ? tripFailures.get(request.getTripId()) : failure, observer);
    }

    @Override
    public void getShape(GetShapeRequest request, StreamObserver<GetShapeResponse> observer) {
        respond(shapes.get(tripKey(request.getHash(), request.getShapeId())), failure, observer);
    }

    private void pause() {
        try {
            Thread.sleep(tripDelay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> void respond(T response, Status failure, StreamObserver<T> observer) {
        if (failure != null) {
            observer.onError(failure.asRuntimeException());
        } else if (response == null) {
            observer.onError(Status.NOT_FOUND.withDescription("Not found").asRuntimeException());
        } else {
            observer.onNext(response);
            observer.onCompleted();
        }
    }
}
