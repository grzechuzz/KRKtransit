package pl.krktransit.schedule.adapter.inbound.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.querying.LookupFailure;

final class GrpcFailureMapper {
    private static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("schedule-error-code", Metadata.ASCII_STRING_MARSHALLER);

    StatusRuntimeException invalidArgument(IllegalArgumentException failure) {
        return error(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", failure.getMessage());
    }

    StatusRuntimeException lookup(LookupFailure failure) {
        Status status = switch (failure.kind()) {
            case UNKNOWN_FEED, ENTITY_NOT_FOUND -> Status.NOT_FOUND;
            case NOT_PUBLISHED, VERSION_UNAVAILABLE -> Status.FAILED_PRECONDITION;
        };
        return error(status, failure.kind().name(), failure.getMessage());
    }

    StatusRuntimeException storage(ImportFailure failure) {
        if (failure.kind() != ImportFailure.Kind.PERSISTENCE) throw failure;
        return error(Status.UNAVAILABLE, "STORAGE_UNAVAILABLE", "Schedule storage is temporarily unavailable");
    }

    private StatusRuntimeException error(Status status, String code, String description) {
        var trailers = new Metadata();
        trailers.put(ERROR_CODE, code);
        return status.withDescription(description).asRuntimeException(trailers);
    }
}
