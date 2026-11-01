package pl.krktransit.stopevents.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import io.grpc.ServerBuilder;
import java.net.InetAddress;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import pl.krktransit.stopevents.adapter.inbound.grpc.BearerTokenInterceptor;
import pl.krktransit.stopevents.adapter.inbound.grpc.LineEventsGrpcEndpoint;
import pl.krktransit.stopevents.adapter.inbound.grpc.StatisticsGrpcEndpoint;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcLineEventsReader;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcStatisticsReader;
import pl.krktransit.stopevents.adapter.outbound.redis.RedisStatisticsCache;
import pl.krktransit.stopevents.application.lineevents.LineEvents;
import pl.krktransit.stopevents.application.statistics.StatisticsQueries;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatisticsProperties.class)
class StatisticsConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource statisticsDataSource(DataSourceProperties database, StatisticsProperties properties) {
        var source = database.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setPoolName("statistics-reads");
        source.setReadOnly(true);
        source.setMaximumPoolSize(properties.readPoolSize());
        source.setMinimumIdle(0);
        source.setConnectionTimeout(properties.connectionWait().toMillis());
        return source;
    }

    @Bean(destroyMethod = "close")
    RedisStatisticsCache statisticsCache(RedisConnections connections, StatisticsProperties properties) {
        var cache = properties.cache();
        return new RedisStatisticsCache(connections::open, cache.prefix(), cache.maximumEntries(),
                cache.maximumEntryBytes(), cache.commandTimeout());
    }

    @Bean(destroyMethod = "close")
    StatisticsQueries statisticsQueries(@Qualifier("statisticsDataSource") HikariDataSource source,
                                        RedisStatisticsCache cache, Clock clock, StopEventsProperties stopEvents,
                                        StatisticsProperties properties) {
        return new StatisticsQueries(new JdbcStatisticsReader(source, stopEvents.sourceIds()), cache, clock,
                Executors.newVirtualThreadPerTaskExecutor(), properties.maximumCalculations());
    }

    @Bean
    StatisticsGrpcEndpoint statisticsEndpoint(StatisticsQueries queries) { return new StatisticsGrpcEndpoint(queries); }

    @Bean
    LineEvents lineEvents(@Qualifier("statisticsDataSource") HikariDataSource source, StatisticsProperties properties) {
        return new LineEvents(new JdbcLineEventsReader(source), properties.maximumCalculations());
    }

    @Bean
    LineEventsGrpcEndpoint lineEventsEndpoint(LineEvents lineEvents) { return new LineEventsGrpcEndpoint(lineEvents); }

    @Bean(destroyMethod = "close")
    ExecutorService statisticsRpcExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    <T extends ServerBuilder<T>> ServerBuilderCustomizer<T> statisticsServerExecutor(ExecutorService statisticsRpcExecutor) {
        return builder -> builder.executor(statisticsRpcExecutor);
    }

    @Bean
    @GlobalServerInterceptor
    BearerTokenInterceptor statisticsAuthentication(StatisticsProperties properties,
            @Value("${spring.grpc.server.address:127.0.0.1}") InetAddress address) {
        requireTokenOnExternalBind(address, properties.grpcToken());
        return new BearerTokenInterceptor(properties.grpcToken());
    }

    private void requireTokenOnExternalBind(InetAddress address, String token) {
        if (missingExternalToken(address, token)) {
            throw new IllegalArgumentException("STOP_EVENTS_GRPC_TOKEN is required when binding beyond loopback");
        }
    }

    private boolean missingExternalToken(InetAddress address, String token) {
        return !address.isLoopbackAddress() && token.isBlank();
    }
}
