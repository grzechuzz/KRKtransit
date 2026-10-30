package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class GrpcCalls<S extends AbstractStub<S>> {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final S stub;
    private final Duration deadline;

    GrpcCalls(S stub, GrpcCallSettings settings) {
        this.stub = authenticated(stub, settings.token());
        this.deadline = settings.deadline();
    }

    S next() { return stub.withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS); }

    private static <S extends AbstractStub<S>> S authenticated(S stub, String token) {
        if (!hasText(token)) return stub;
        var headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
