package pl.krktransit.stopevents.adapter.outbound.schedule;

import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import pl.krktransit.schedule.contracts.v1.FeedId;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.application.port.ScheduleFailure;
import pl.krktransit.stopevents.domain.source.SourceId;

public final class GrpcScheduleContexts implements ScheduleContexts {
    private static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("schedule-error-code", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Set<Status.Code> TEMPORARY = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED, Status.Code.INTERNAL, Status.Code.UNKNOWN, Status.Code.ABORTED,
            Status.Code.CANCELLED);
    private final ScheduleServiceGrpc.ScheduleServiceBlockingStub stub;
    private final Duration deadline;
    private final TripContextMapper mapper = new TripContextMapper();

    public GrpcScheduleContexts(Channel channel, String token, Duration deadline) {
        var headers = new Metadata();
        if (token != null && !token.isBlank()) headers.put(AUTHORIZATION, "Bearer " + token);
        this.stub = ScheduleServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        this.deadline = deadline;
    }

    @Override
    public String currentHash(SourceId source) {
        var request = GetCurrentHashRequest.newBuilder().setFeedId(FeedId.newBuilder()
                .setCityId(source.cityId()).setSourceId(source.sourceId())).build();
        try {
            String hash = call().getCurrentHash(request).getHash();
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalStateException("Schedule returned an invalid hash");
            return hash;
        } catch (StatusRuntimeException failure) {
            throw translate(failure);
        }
    }

    @Override
    public Lookup tripContext(String hash, String tripId) {
        var request = GetTripContextRequest.newBuilder().setHash(hash).setTripId(tripId).build();
        try {
            return context(call().getTripContext(request), hash, tripId);
        } catch (StatusRuntimeException failure) {
            String code = errorCode(failure);
            if (code.equals("ENTITY_NOT_FOUND")) return new UnknownTrip();
            if (code.equals("VERSION_UNAVAILABLE")) return new VersionUnavailable();
            throw translate(failure);
        }
    }

    private Lookup context(GetTripContextResponse response, String hash, String tripId) {
        try {
            return new Found(mapper.map(response, hash, tripId));
        } catch (IllegalStateException invalid) {
            return new InvalidContext(invalid.getMessage());
        }
    }

    private ScheduleServiceGrpc.ScheduleServiceBlockingStub call() {
        return stub.withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS);
    }

    private String errorCode(StatusRuntimeException failure) {
        var trailers = failure.getTrailers();
        String value = trailers == null ? null : trailers.get(ERROR_CODE);
        return value == null ? "" : value;
    }

    private RuntimeException translate(StatusRuntimeException failure) {
        if (temporary(failure)) return new ScheduleFailure("Schedule temporarily unavailable", failure);
        return new IllegalStateException("Schedule rejected the detector request: " + failure.getStatus().getCode(), failure);
    }

    private boolean temporary(StatusRuntimeException failure) {
        return TEMPORARY.contains(failure.getStatus().getCode()) || errorCode(failure).equals("NOT_PUBLISHED");
    }
}
