package pl.krktransit.schedule.bootstrap;

import io.grpc.ServerBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.ServerBuilderCustomizer;

@Configuration(proxyBeanMethods = false)
class GrpcExecutionConfiguration {
    @Bean(destroyMethod = "close")
    ExecutorService scheduleGrpcExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("schedule-grpc-", 0).factory());
    }

    @Bean
    <T extends ServerBuilder<T>> ServerBuilderCustomizer<T> scheduleGrpcExecution(
            @Qualifier("scheduleGrpcExecutor") ExecutorService executor) {
        return builder -> builder.executor(executor);
    }
}
