package pl.krktransit.schedule.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;

class BearerTokenInterceptorTest {
    private Server server;
    private ManagedChannel channel;
    private ScheduleServiceGrpc.ScheduleServiceBlockingStub client;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(
                new HashEndpoint(), new BearerTokenInterceptor("local-test-token"))).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ScheduleServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsMissingCredentials() {
        var failure = assertThrows(StatusRuntimeException.class,
                () -> client.getCurrentHash(GetCurrentHashRequest.getDefaultInstance()));
        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void rejectsIncorrectCredentials() {
        var failure = assertThrows(StatusRuntimeException.class,
                () -> authenticated("Bearer wrong").getCurrentHash(GetCurrentHashRequest.getDefaultInstance()));
        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void permitsTheConfiguredServiceToken() {
        var response = authenticated("Bearer local-test-token").getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        assertThat(response.getHash()).isEqualTo("a".repeat(64));
    }

    private ScheduleServiceGrpc.ScheduleServiceBlockingStub authenticated(String token) {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), token);
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static final class HashEndpoint extends ScheduleServiceGrpc.ScheduleServiceImplBase {
        @Override
        public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> observer) {
            observer.onNext(GetCurrentHashResponse.newBuilder().setHash("a".repeat(64)).build());
            observer.onCompleted();
        }
    }
}
