package pl.krktransit.schedule.adapter.inbound.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class BearerTokenInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final byte[] expected;

    public BearerTokenInterceptor(String token) {
        expected = token.isBlank() ? new byte[0] : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (expected.length == 0 || matches(headers.get(AUTHORIZATION))) return next.startCall(call, headers);
        call.close(Status.UNAUTHENTICATED.withDescription("A valid service bearer token is required"), new Metadata());
        return new ServerCall.Listener<>() {};
    }

    private boolean matches(String authorization) {
        return authorization != null
                && MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8));
    }
}
