package pl.krktransit.livevehicles.bootstrap;

import java.net.InetAddress;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import pl.krktransit.livevehicles.adapter.inbound.grpc.BearerTokenInterceptor;
import pl.krktransit.livevehicles.adapter.inbound.grpc.LiveVehiclesGrpcEndpoint;
import pl.krktransit.livevehicles.adapter.inbound.stream.ProjectionLoop;
import pl.krktransit.livevehicles.adapter.inbound.stream.StreamConsumer;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryVehiclePositions;
import pl.krktransit.livevehicles.application.LiveVehicleQueries;
import pl.krktransit.livevehicles.application.ObservationSource;
import pl.krktransit.livevehicles.application.VehiclePositions;

@Configuration(proxyBeanMethods = false)
class LiveVehiclesConfiguration {
    @Bean
    Clock liveVehiclesClock() { return Clock.systemUTC(); }

    @Bean
    VehiclePositions vehiclePositions(LiveVehiclesProperties properties) {
        return new InMemoryVehiclePositions(properties.retention(), properties.maximumVehicles());
    }

    @Bean(destroyMethod = "close")
    RedisConnections redisConnections(LiveVehiclesProperties properties) { return new RedisConnections(properties.redis()); }

    @Bean(destroyMethod = "close")
    StreamConsumer liveObservations(RedisConnections connections, LiveVehiclesProperties properties) {
        return new StreamConsumer(connections::open, properties.streamPrefix(), properties.sourceIds(),
                properties.readCount(), properties.readBlock());
    }

    @Bean
    LiveVehicleQueries liveVehicleQueries(VehiclePositions positions, Clock clock) {
        return new LiveVehicleQueries(positions, clock);
    }

    @Bean
    LiveVehiclesGrpcEndpoint liveVehiclesGrpcEndpoint(LiveVehicleQueries queries) { return new LiveVehiclesGrpcEndpoint(queries); }

    @Bean
    @GlobalServerInterceptor
    BearerTokenInterceptor liveVehiclesAuthentication(LiveVehiclesProperties properties,
            @Value("${spring.grpc.server.address:127.0.0.1}") InetAddress address) {
        requireTokenOnExternalBind(address, properties.grpcToken());
        return new BearerTokenInterceptor(properties.grpcToken());
    }

    private void requireTokenOnExternalBind(InetAddress address, String token) {
        if (missingExternalToken(address, token)) {
            throw new IllegalArgumentException("LIVE_VEHICLES_GRPC_TOKEN is required when binding beyond loopback");
        }
    }

    private boolean missingExternalToken(InetAddress address, String token) {
        return !address.isLoopbackAddress() && token.isBlank();
    }

    @Bean(destroyMethod = "close")
    ProjectionLoop projectionLoop(ObservationSource source, VehiclePositions positions,
                                  LiveVehiclesProperties properties, Clock clock) {
        return new ProjectionLoop(source, positions, properties.sourceIds(), properties.retention(),
                properties.retryDelay(), properties.shutdownTimeout(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "live-vehicles.jobs-enabled", havingValue = "true", matchIfMissing = true)
    ProjectionJobs projectionJobs(ProjectionLoop loop, ConfigurableApplicationContext context) {
        return new ProjectionJobs(loop, context);
    }
}
