package pl.krktransit.stopevents.adapter.inbound.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.OptionalLong;
import pl.krktransit.stopevents.application.lineevents.LineEvents;
import pl.krktransit.stopevents.application.lineevents.LineEventsFailure;
import pl.krktransit.stopevents.contracts.v1.LineEventsServiceGrpc;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsRequest;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsResponse;
import pl.krktransit.stopevents.domain.lineevents.InvalidLineEventsQuery;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

public final class LineEventsGrpcEndpoint extends LineEventsServiceGrpc.LineEventsServiceImplBase {
    private static final System.Logger LOG = System.getLogger(LineEventsGrpcEndpoint.class.getName());
    private static final long MINIMUM_SECONDS = -62_135_596_800L;
    private static final long MAXIMUM_SECONDS = 253_402_300_799L;
    private static final int NANOS_PER_SECOND = 1_000_000_000;
    private final LineEvents lineEvents;
    private final LineEventsResponseMapper mapper = new LineEventsResponseMapper();

    public LineEventsGrpcEndpoint(LineEvents lineEvents) { this.lineEvents = lineEvents; }

    @Override
    public void listLineEvents(ListLineEventsRequest request, StreamObserver<ListLineEventsResponse> observer) {
        try {
            var page = lineEvents.list(query(request));
            observer.onNext(mapper.response(request, page));
            observer.onCompleted();
        } catch (InvalidLineEventsQuery failure) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(failure.getMessage()).asRuntimeException());
        } catch (LineEventsFailure failure) {
            observer.onError(status(failure).withDescription(failure.getMessage()).asRuntimeException());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Line event read failed", failure);
            observer.onError(Status.INTERNAL.withDescription("Line event read failed").asRuntimeException());
        }
    }

    private LineEventsQuery query(ListLineEventsRequest request) {
        return new LineEventsQuery(request.hasCityId() ? request.getCityId() : null,
                request.hasLineNumber() ? request.getLineNumber() : null, createdAfter(request),
                request.hasAfterId() ? OptionalLong.of(request.getAfterId()) : OptionalLong.empty(),
                request.hasPageSize() ? request.getPageSize() : LineEventsQuery.DEFAULT_PAGE_SIZE);
    }

    private Instant createdAfter(ListLineEventsRequest request) {
        if (!request.hasCreatedAfter()) return null;
        var time = request.getCreatedAfter();
        if (!validTimestamp(time)) throw new InvalidLineEventsQuery("created_after must be a valid timestamp");
        return Instant.ofEpochSecond(time.getSeconds(), time.getNanos());
    }

    private boolean validTimestamp(Timestamp time) {
        return time.getSeconds() >= MINIMUM_SECONDS && time.getSeconds() <= MAXIMUM_SECONDS
                && time.getNanos() >= 0 && time.getNanos() < NANOS_PER_SECOND;
    }

    private Status status(LineEventsFailure failure) {
        return switch (failure.kind()) {
            case UNAVAILABLE -> Status.UNAVAILABLE;
            case BUSY -> Status.RESOURCE_EXHAUSTED;
        };
    }
}
