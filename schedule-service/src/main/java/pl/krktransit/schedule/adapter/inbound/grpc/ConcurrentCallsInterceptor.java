package pl.krktransit.schedule.adapter.inbound.grpc;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConcurrentCallsInterceptor implements ServerInterceptor {
    private final Semaphore permits;

    public ConcurrentCallsInterceptor(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Maximum concurrent gRPC calls must be positive");
        permits = new Semaphore(maximum);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (!permits.tryAcquire()) {
            call.close(Status.RESOURCE_EXHAUSTED.withDescription("Maximum concurrent schedule RPCs reached"),
                    new Metadata());
            return new ServerCall.Listener<>() {};
        }

        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) permits.release();
        };
        try {
            var listener = next.startCall(call, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
                @Override
                public void onMessage(ReqT message) { invoke(() -> super.onMessage(message)); }

                @Override
                public void onHalfClose() { invoke(super::onHalfClose); }

                @Override
                public void onReady() { invoke(super::onReady); }

                @Override
                public void onCancel() {
                    try { super.onCancel(); } finally { release.run(); }
                }

                @Override
                public void onComplete() {
                    try { super.onComplete(); } finally { release.run(); }
                }

                private void invoke(Runnable callback) {
                    try { callback.run(); } catch (RuntimeException | Error failure) {
                        release.run();
                        throw failure;
                    }
                }
            };
        } catch (RuntimeException | Error failure) {
            release.run();
            throw failure;
        }
    }
}
