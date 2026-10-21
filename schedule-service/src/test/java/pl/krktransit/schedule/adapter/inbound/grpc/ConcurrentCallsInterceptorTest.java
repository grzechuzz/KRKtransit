package pl.krktransit.schedule.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashResponse;
import pl.krktransit.schedule.contracts.v1.GetShapeRequest;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextRequest;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;

class ConcurrentCallsInterceptorTest {
    private final HeldEndpoint endpoint = new HeldEndpoint();
    private Server server;
    private ManagedChannel channel;
    private ScheduleServiceGrpc.ScheduleServiceFutureStub client;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.interceptForward(endpoint,
                        new BearerTokenInterceptor("test-token"), new ConcurrentCallsInterceptor(2)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer test-token");
        client = ScheduleServiceGrpc.newFutureStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void sharesOneBudgetAcrossMethodsAndAcceptsCallsAfterCompletion() throws Exception {
        var hash = client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        var first = endpoint.take();
        var trip = client.getTripContext(GetTripContextRequest.getDefaultInstance());
        var second = endpoint.take();

        assertStatus(client.getShape(GetShapeRequest.getDefaultInstance()), Status.Code.RESOURCE_EXHAUSTED);
        assertThat(endpoint.pending).isEmpty();

        first.complete().run();
        assertThat(hash.get(5, TimeUnit.SECONDS).getHash()).isEqualTo("a".repeat(64));
        var shape = client.getShape(GetShapeRequest.getDefaultInstance());
        var third = endpoint.take();
        second.complete().run();
        third.complete().run();
        assertThat(trip.get(5, TimeUnit.SECONDS).getTripId()).isEqualTo("trip");
        assertThat(shape.get(5, TimeUnit.SECONDS).getShapeId()).isEqualTo("shape");
    }

    @Test
    void cancellationReleasesTheCallBudget() throws Exception {
        var cancelled = client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        endpoint.take();
        var other = client.getTripContext(GetTripContextRequest.getDefaultInstance());
        var second = endpoint.take();

        assertThat(cancelled.cancel(true)).isTrue();

        var replacement = client.getShape(GetShapeRequest.getDefaultInstance());
        var third = endpoint.take();
        second.complete().run();
        third.complete().run();
        assertThat(other.get(5, TimeUnit.SECONDS).getTripId()).isEqualTo("trip");
        assertThat(replacement.get(5, TimeUnit.SECONDS).getShapeId()).isEqualTo("shape");
    }

    @Test
    void applicationErrorsReleaseTheCallBudget() throws Exception {
        var failed = client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        var first = endpoint.take();
        var other = client.getTripContext(GetTripContextRequest.getDefaultInstance());
        var second = endpoint.take();

        first.observer().onError(Status.UNAVAILABLE.asRuntimeException());
        assertStatus(failed, Status.Code.UNAVAILABLE);

        var replacement = client.getShape(GetShapeRequest.getDefaultInstance());
        var third = endpoint.take();
        second.complete().run();
        third.complete().run();
        other.get(5, TimeUnit.SECONDS);
        replacement.get(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsUnauthenticatedCallsEvenWhenTheBudgetIsFull() {
        client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        client.getTripContext(GetTripContextRequest.getDefaultInstance());

        var anonymous = ScheduleServiceGrpc.newFutureStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
        assertStatus(anonymous.getShape(GetShapeRequest.getDefaultInstance()), Status.Code.UNAUTHENTICATED);
        assertThat(endpoint.pending).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "message", "half-close", "ready", "cancel", "complete"})
    void unexpectedFailuresReleaseExactlyOnePlace(String phase) {
        var limiter = new ConcurrentCallsInterceptor(1);
        var failure = new IllegalStateException("handler failure");
        var delegate = new ServerCall.Listener<Object>() {
            private void failAt(String callback) { if (phase.equals(callback)) throw failure; }
            @Override public void onMessage(Object message) { failAt("message"); }
            @Override public void onHalfClose() { failAt("half-close"); }
            @Override public void onReady() { failAt("ready"); }
            @Override public void onCancel() { failAt("cancel"); }
            @Override public void onComplete() { failAt("complete"); }
        };
        ServerCallHandler<Object, Object> handler = (call, headers) -> {
            if (phase.equals("start")) throw failure;
            return delegate;
        };
        if (phase.equals("start")) {
            assertThatThrownBy(() -> limiter.interceptCall(call(), new Metadata(), handler)).isSameAs(failure);
        } else {
            var listener = limiter.interceptCall(call(), new Metadata(), handler);
            assertThatThrownBy(() -> {
                switch (phase) {
                    case "message" -> listener.onMessage(new Object());
                    case "half-close" -> listener.onHalfClose();
                    case "ready" -> listener.onReady();
                    case "cancel" -> listener.onCancel();
                    case "complete" -> listener.onComplete();
                    default -> throw new AssertionError(phase);
                }
            }).isSameAs(failure);
            if (!phase.equals("cancel") && !phase.equals("complete")) listener.onComplete();
        }

        ServerCallHandler<Object, Object> healthy = (call, headers) -> new ServerCall.Listener<>() {};
        var accepted = call();
        var admitted = limiter.interceptCall(accepted, new Metadata(), healthy);
        verify(accepted, never()).close(any(Status.class), any(Metadata.class));
        var rejected = call();
        limiter.interceptCall(rejected, new Metadata(), healthy);
        verify(rejected).close(argThat(status -> status.getCode() == Status.Code.RESOURCE_EXHAUSTED), any(Metadata.class));
        admitted.onComplete();
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> call() { return mock(ServerCall.class); }

    private static void assertStatus(Future<?> response, Status.Code expected) {
        var failure = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
        assertThat(Status.fromThrowable(failure.getCause()).getCode()).isEqualTo(expected);
    }

    private record Pending(Runnable complete, StreamObserver<?> observer) {}

    private static final class HeldEndpoint extends ScheduleServiceGrpc.ScheduleServiceImplBase {
        private final Queue<Pending> pending = new ConcurrentLinkedQueue<>();

        Pending take() {
            var next = pending.poll();
            assertThat(next).as("request reached the service").isNotNull();
            return next;
        }

        private <T> void hold(T response, StreamObserver<T> observer) {
            pending.add(new Pending(() -> {
                observer.onNext(response);
                observer.onCompleted();
            }, observer));
        }

        @Override
        public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> observer) {
            hold(GetCurrentHashResponse.newBuilder().setHash("a".repeat(64)).build(), observer);
        }

        @Override
        public void getTripContext(GetTripContextRequest request, StreamObserver<GetTripContextResponse> observer) {
            hold(GetTripContextResponse.newBuilder().setTripId("trip").build(), observer);
        }

        @Override
        public void getShape(GetShapeRequest request, StreamObserver<GetShapeResponse> observer) {
            hold(GetShapeResponse.newBuilder().setShapeId("shape").build(), observer);
        }
    }
}
