package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import pl.krktransit.publicapi.application.ApiFailure;

final class GrpcFailures {
    private static final String INVALID_REQUEST = "Invalid request";
    private final String service;

    GrpcFailures(String service) { this.service = service; }

    static boolean isNotFound(StatusRuntimeException failure) { return failure.getStatus().getCode() == Status.Code.NOT_FOUND; }

    ApiFailure translate(StatusRuntimeException failure) {
        return switch (failure.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, ABORTED, FAILED_PRECONDITION ->
                    new ApiFailure.UpstreamUnavailable(service, failure);
            case RESOURCE_EXHAUSTED -> new ApiFailure.UpstreamBusy(service, failure);
            case INVALID_ARGUMENT -> new ApiFailure.InvalidRequest(description(failure));
            default -> new ApiFailure.UpstreamRejected(service, failure);
        };
    }

    private static String description(StatusRuntimeException failure) {
        String description = failure.getStatus().getDescription();
        return description == null || description.isBlank() ? INVALID_REQUEST : description;
    }
}
