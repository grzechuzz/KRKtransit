package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.function.Supplier;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc.ScheduleServiceBlockingStub;

public final class GrpcScheduleSource implements ScheduleSource {
    private static final String SERVICE = "Schedule service";
    private final GrpcCalls<ScheduleServiceBlockingStub> calls;
    private final GrpcFailures failures = new GrpcFailures(SERVICE);
    private final ScheduleProtoMapper mapper = new ScheduleProtoMapper();

    public GrpcScheduleSource(Channel channel, GrpcCallSettings settings) {
        this.calls = new GrpcCalls<>(ScheduleServiceGrpc.newBlockingStub(channel), settings);
    }

    @Override
    public Optional<String> currentHash(FeedId feed) {
        return lookup(() -> calls.next().getCurrentHash(mapper.currentHashRequest(feed)).getHash());
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
}
