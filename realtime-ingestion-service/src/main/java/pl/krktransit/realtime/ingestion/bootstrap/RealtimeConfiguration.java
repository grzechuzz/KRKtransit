package pl.krktransit.realtime.ingestion.bootstrap;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import pl.krktransit.realtime.ingestion.adapter.inbound.scheduling.EndpointPollers;
import pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt.GtfsRealtimeDecoder;
import pl.krktransit.realtime.ingestion.adapter.outbound.http.HttpFeedFetcher;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.PublicationStatistics;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.RedisClients;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.RedisObservationSink;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.StreamRoutes;
import pl.krktransit.realtime.ingestion.application.collection.CollectEndpoint;
import pl.krktransit.realtime.ingestion.application.collection.EndpointBackoff;

@Configuration(proxyBeanMethods = false)
class RealtimeConfiguration {
    @Bean
    Clock realtimeClock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close")
    HttpFeedFetcher feedFetcher(RealtimeProperties properties, Clock clock) {
        var http = properties.http();
        var client = HttpClient.newBuilder().connectTimeout(http.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new HttpFeedFetcher(client, http.downloadTimeout(), http.maxBodyBytes(), http.maxConcurrent(),
                http.maxPerHost(), clock);
    }

    @Bean
    GtfsRealtimeDecoder feedDecoder() { return new GtfsRealtimeDecoder(); }

    @Bean
    StreamRoutes streamRoutes(RealtimeProperties properties) {
        return new StreamRoutes(properties.redis().streamPrefix(), properties.streamMaxLengths());
    }

    @Bean(destroyMethod = "close")
    RedisClients redisClients(RealtimeProperties properties) {
        var redis = properties.redis();
        return new RedisClients(redis.url(), redis.username(), redis.password(), redis.clientName(),
                redis.connectTimeout(), redis.commandTimeout());
    }

    @Bean
    PublicationStatistics publicationStatistics() { return new PublicationStatistics(); }

    @Bean(destroyMethod = "close")
    RedisObservationSink observationSink(RealtimeProperties properties, StreamRoutes routes, RedisClients clients,
                                         PublicationStatistics statistics) {
        var redis = properties.redis();
        return new RedisObservationSink(routes, clients, redis.publicationBufferBytes(), redis.maxInFlight(),
                redis.recoveryDelay(), redis.shutdownDrain(), statistics);
    }

    @Bean
    CollectEndpoint collectEndpoint(HttpFeedFetcher fetcher, GtfsRealtimeDecoder decoder,
                                    RedisObservationSink sink, Clock clock) {
        return new CollectEndpoint(fetcher, decoder, sink, clock);
    }

    @Bean(destroyMethod = "close")
    EndpointPollers endpointPollers(RealtimeProperties properties, CollectEndpoint collect, RedisObservationSink sink) {
        return new EndpointPollers(properties.endpoints(), collect, sink, () -> new EndpointBackoff(
                properties.pollDelay(), properties.failureDelays(), properties.failureJitter(),
                RandomGenerator.getDefault()), properties.shutdownTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "realtime.jobs-enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler realtimeReportExecutor() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("realtime-report-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "realtime.jobs-enabled", havingValue = "true", matchIfMissing = true)
    CollectionJobs collectionJobs(RealtimeProperties properties, RedisObservationSink sink, EndpointPollers pollers,
                                  PublicationStatistics statistics, ThreadPoolTaskScheduler realtimeReportExecutor,
                                  ConfigurableApplicationContext context) {
        return new CollectionJobs(properties, sink, pollers, statistics, realtimeReportExecutor, context);
    }
}
