package pl.krktransit.stopevents.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ManagedChannel;
import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import pl.krktransit.stopevents.adapter.inbound.stream.DetectorLoop;
import pl.krktransit.stopevents.adapter.inbound.stream.StreamConsumer;
import pl.krktransit.stopevents.adapter.outbound.postgres.AdvisoryDetectorOwnership;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcStopEventStore;
import pl.krktransit.stopevents.adapter.outbound.redis.RedisDetectorStateStore;
import pl.krktransit.stopevents.adapter.outbound.schedule.CachedScheduleContexts;
import pl.krktransit.stopevents.adapter.outbound.schedule.GrpcScheduleContexts;
import pl.krktransit.stopevents.application.port.DetectorOwnership;
import pl.krktransit.stopevents.application.port.DetectorStateStore;
import pl.krktransit.stopevents.application.port.ObservationSource;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.application.port.StopEventStore;
import pl.krktransit.stopevents.application.processing.ProcessObservationBatch;

@Configuration(proxyBeanMethods = false)
class StopEventsConfiguration {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties database) {
        return database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    Clock stopEventsClock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close")
    RedisConnections redisConnections(StopEventsProperties properties) { return new RedisConnections(properties.redis()); }

    @Bean(destroyMethod = "close")
    RedisDetectorStateStore detectorStateStore(StopEventsProperties properties, RedisConnections connections) {
        return new RedisDetectorStateStore(connections::open, properties.statePrefix(), properties.inactivity());
    }

    @Bean(destroyMethod = "close")
    StreamConsumer observationSource(StopEventsProperties properties, RedisConnections connections) {
        return new StreamConsumer(connections::open, properties.streamPrefix(), properties.sourceIds(),
                properties.readCount(), properties.readBlock());
    }

    @Bean
    JdbcStopEventStore stopEventStore(DataSource source) { return new JdbcStopEventStore(source); }

    @Bean(destroyMethod = "close")
    AdvisoryDetectorOwnership detectorOwnership(DataSourceProperties database) {
        return new AdvisoryDetectorOwnership(database.initializeDataSourceBuilder().type(PGSimpleDataSource.class).build());
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel scheduleChannel(GrpcChannelFactory channels, StopEventsProperties properties) {
        return channels.createChannel(properties.schedule().address());
    }

    @Bean
    ScheduleContexts scheduleContexts(ManagedChannel scheduleChannel, StopEventsProperties properties, Clock clock) {
        var settings = properties.schedule();
        var client = new GrpcScheduleContexts(scheduleChannel, settings.token(), settings.deadline());
        return new CachedScheduleContexts(client, settings.contextCacheSize(), settings.hashRefresh(), clock);
    }

    @Bean
    ProcessObservationBatch processObservationBatch(DetectorStateStore state, StopEventStore events,
                                                    ScheduleContexts schedule, StopEventsProperties properties) {
        return new ProcessObservationBatch(state, events, schedule, properties.inactivity());
    }

    @Bean(destroyMethod = "close")
    DetectorLoop detectorLoop(DetectorOwnership ownership, ObservationSource input, DetectorStateStore state,
                              StopEventStore events, ProcessObservationBatch process, StopEventsProperties properties,
                              Clock clock) {
        return new DetectorLoop(ownership, input, state, events, process, properties.sourceIds(),
                properties.failureDelays(), properties.shutdownTimeout(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "stop-events.jobs-enabled", havingValue = "true", matchIfMissing = true)
    DetectionJobs detectionJobs(DetectorLoop loop, ConfigurableApplicationContext context) {
        return new DetectionJobs(loop, context);
    }
}
