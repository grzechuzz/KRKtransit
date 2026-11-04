package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.LineRoutes;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;
import pl.krktransit.publicapi.application.schedule.TripSummary;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc.ScheduleServiceBlockingStub;

public final class GrpcScheduleSource implements ScheduleSource {
    private static final System.Logger LOG = System.getLogger(GrpcScheduleSource.class.getName());
    private static final String SERVICE = "Schedule service";
    private final GrpcCalls<ScheduleServiceBlockingStub> calls;
    private final GrpcFailures failures = new GrpcFailures(SERVICE);
    private final ScheduleProtoMapper mapper = new ScheduleProtoMapper();
    private final Semaphore lookupPermits;

    public GrpcScheduleSource(Channel channel, GrpcCallSettings settings, int maximumConcurrentLookups) {
        if (maximumConcurrentLookups <= 0) throw new IllegalArgumentException("Concurrent lookups must be positive");
        this.calls = new GrpcCalls<>(ScheduleServiceGrpc.newBlockingStub(channel), settings);
        this.lookupPermits = new Semaphore(maximumConcurrentLookups);
    }

    @Override
    public LineRoutes lineRoutes(String lineNumber) {
        try {
            return mapper.lineRoutes(calls.next().getLineRoutes(mapper.lineRoutesRequest(lineNumber)));
        } catch (StatusRuntimeException failure) {
            throw failures.translate(failure);
        }
    }

    @Override
    public Optional<String> currentHash(FeedId feed) {
        return lookup(() -> calls.next().getCurrentHash(mapper.currentHashRequest(feed)).getHash());
    }

    @Override
    public TripSummaries tripSummaries(String hash, Set<String> tripIds) {
        var lookups = lookUpConcurrently(hash, tripIds);
        logUnresolved(lookups);
        return summaries(lookups);
    }

    @Override
    public Optional<TripStops> tripStops(String hash, String tripId) {
        return lookup(() -> mapper.tripStops(tripContext(hash, tripId)));
    }

    @Override
    public Optional<Shape> shape(String hash, String shapeId) {
        return lookup(() -> mapper.shape(calls.next().getShape(mapper.shapeRequest(hash, shapeId))));
    }

    private GetTripContextResponse tripContext(String hash, String tripId) {
        return calls.next().getTripContext(mapper.tripRequest(hash, tripId));
    }

    private <T> Optional<T> lookup(Supplier<T> call) {
        try {
            return Optional.of(call.get());
        } catch (StatusRuntimeException failure) {
            if (GrpcFailures.isNotFound(failure)) return Optional.empty();
            throw failures.translate(failure);
        }
    }

    private List<TripLookup> lookUpConcurrently(String hash, Set<String> tripIds) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = tripIds.stream()
                    .map(tripId -> CompletableFuture.supplyAsync(() -> summaryLookup(hash, tripId), executor)).toList();
            return pending.stream().map(CompletableFuture::join).toList();
        }
    }

    private TripLookup summaryLookup(String hash, String tripId) {
        lookupPermits.acquireUninterruptibly();
        try {
            return lookup(() -> mapper.summary(tripContext(hash, tripId)))
                    .<TripLookup>map(summary -> new Found(tripId, summary)).orElseGet(() -> new Missing(tripId));
        } catch (ApiFailure failure) {
            return new Unresolved(failure);
        } finally {
            lookupPermits.release();
        }
    }

    private TripSummaries summaries(List<TripLookup> lookups) {
        var found = new HashMap<String, TripSummary>();
        var missing = new HashSet<String>();
        for (var lookup : lookups) {
            switch (lookup) {
                case Found trip -> found.put(trip.tripId(), trip.summary());
                case Missing trip -> missing.add(trip.tripId());
                case Unresolved ignored -> { }
            }
        }
        return new TripSummaries(found, missing);
    }

    private void logUnresolved(List<TripLookup> lookups) {
        var unresolved = lookups.stream().filter(Unresolved.class::isInstance).map(Unresolved.class::cast).toList();
        if (unresolved.isEmpty()) return;
        LOG.log(System.Logger.Level.WARNING, "Could not resolve {0} trips: {1}", unresolved.size(),
                unresolved.getFirst().failure().getMessage());
    }

    private sealed interface TripLookup permits Found, Missing, Unresolved {}

    private record Found(String tripId, TripSummary summary) implements TripLookup {}

    private record Missing(String tripId) implements TripLookup {}

    private record Unresolved(ApiFailure failure) implements TripLookup {}
}
