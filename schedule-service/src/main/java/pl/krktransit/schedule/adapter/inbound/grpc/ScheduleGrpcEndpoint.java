package pl.krktransit.schedule.adapter.inbound.grpc;

import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.querying.LookupFailure;
import pl.krktransit.schedule.application.querying.ScheduleQueries;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashResponse;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.schedule.domain.feed.FeedId;

public final class ScheduleGrpcEndpoint extends ScheduleServiceGrpc.ScheduleServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduleGrpcEndpoint.class);
    private final ScheduleQueries queries;
    private final GrpcResponseMapper responses = new GrpcResponseMapper();
    private final GrpcFailureMapper failures = new GrpcFailureMapper();

    public ScheduleGrpcEndpoint(ScheduleQueries queries) {
        this.queries = queries;
    }

    @Override
    public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> observer) {
        respond("GetCurrentHash", observer, () -> GetCurrentHashResponse.newBuilder()
                .setHash(queries.getCurrentHash(feedId(request)).value()).build());
    }

    @Override
    public void getTripContext(GetTripContextRequest request, StreamObserver<GetTripContextResponse> observer) {
        respond("GetTripContext", observer, () -> responses.trip(queries.getTripContext(request.getHash(), request.getTripId())));
    }

    @Override
    public void getShape(GetShapeRequest request, StreamObserver<GetShapeResponse> observer) {
        respond("GetShape", observer, () -> responses.shape(queries.getShape(request.getHash(), request.getShapeId())));
    }

    private FeedId feedId(GetCurrentHashRequest request) {
        if (!request.hasFeedId()) throw new IllegalArgumentException("feed_id is required");
        var feed = request.getFeedId();
        return new FeedId(feed.getCityId(), feed.getSourceId());
    }

    private <T> void respond(String operation, StreamObserver<T> observer, Supplier<T> action) {
        T response;
        try {
            response = action.get();
        } catch (IllegalArgumentException failure) {
            observer.onError(failures.invalidArgument(failure));
            return;
        } catch (LookupFailure failure) {
            observer.onError(failures.lookup(failure));
            return;
        } catch (ImportFailure failure) {
            var error = failures.storage(failure);
            LOG.error("Schedule storage read failed: operation={}", operation, failure);
            observer.onError(error);
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }
}
