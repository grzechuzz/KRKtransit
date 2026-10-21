package pl.krktransit.schedule.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashRequest;
import pl.krktransit.schedule.contracts.v1.GetCurrentHashResponse;
import pl.krktransit.schedule.contracts.v1.ScheduleServiceGrpc;

class GrpcExecutionConfigurationTest {
    @Test
    void configuresGrpcHandlersOnLifecycleManagedVirtualThreads() {
        var executor = new AtomicReference<ExecutorService>();
        new ApplicationContextRunner().withUserConfiguration(GrpcExecutionConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            executor.set(context.getBean("scheduleGrpcExecutor", ExecutorService.class));
            @SuppressWarnings("unchecked")
            var customizer = (ServerBuilderCustomizer<InProcessServerBuilder>)
                    context.getBean("scheduleGrpcExecution", ServerBuilderCustomizer.class);

            var virtualHandler = new AtomicBoolean();
            invoke(customizer, virtualHandler);
            assertThat(virtualHandler).isTrue();
        });
        assertThat(executor.get().isTerminated()).isTrue();
    }

    private void invoke(ServerBuilderCustomizer<InProcessServerBuilder> customizer, AtomicBoolean virtualHandler)
            throws IOException, InterruptedException {
        String name = InProcessServerBuilder.generateName();
        var builder = InProcessServerBuilder.forName(name).addService(new ThreadProbe(virtualHandler));
        customizer.customize(builder);
        Server server = builder.build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            ScheduleServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getCurrentHash(GetCurrentHashRequest.getDefaultInstance());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static final class ThreadProbe extends ScheduleServiceGrpc.ScheduleServiceImplBase {
        private final AtomicBoolean virtualHandler;

        private ThreadProbe(AtomicBoolean virtualHandler) { this.virtualHandler = virtualHandler; }

        @Override
        public void getCurrentHash(GetCurrentHashRequest request, StreamObserver<GetCurrentHashResponse> observer) {
            virtualHandler.set(Thread.currentThread().isVirtual());
            observer.onNext(GetCurrentHashResponse.newBuilder().setHash("a".repeat(64)).build());
            observer.onCompleted();
        }
    }
}
