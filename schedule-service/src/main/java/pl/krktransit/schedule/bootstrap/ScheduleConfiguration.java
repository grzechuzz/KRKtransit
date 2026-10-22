package pl.krktransit.schedule.bootstrap;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import pl.krktransit.schedule.adapter.inbound.grpc.BearerTokenInterceptor;
import pl.krktransit.schedule.adapter.inbound.grpc.ConcurrentCallsInterceptor;
import pl.krktransit.schedule.adapter.inbound.grpc.ScheduleGrpcEndpoint;
import pl.krktransit.schedule.adapter.inbound.scheduling.ImportCycleRunner;
import pl.krktransit.schedule.adapter.outbound.archive.ContentAddressedArchiveStore;
import pl.krktransit.schedule.adapter.outbound.gtfscsv.StreamingGtfsParser;
import pl.krktransit.schedule.adapter.outbound.http.HttpFeedDownloader;
import pl.krktransit.schedule.adapter.outbound.postgres.PostgresSchedulePublication;
import pl.krktransit.schedule.adapter.outbound.postgres.PostgresScheduleReader;
import pl.krktransit.schedule.application.importing.ImportFeed;
import pl.krktransit.schedule.application.port.ArchiveStore;
import pl.krktransit.schedule.application.port.FeedDownloader;
import pl.krktransit.schedule.application.port.FeedParser;
import pl.krktransit.schedule.application.port.SchedulePublication;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.application.querying.ScheduleQueries;

@Configuration(proxyBeanMethods = false)
class ScheduleConfiguration {
    @Bean
    Clock scheduleClock() { return Clock.systemUTC(); }

    @Bean
    SchedulePublication schedulePublication(DataSource source, Clock clock, ScheduleProperties properties) {
        var publication = new PostgresSchedulePublication(source, clock);
        publication.registerFeeds(properties.feedConfigs().stream().map(config -> config.id()).toList());
        return publication;
    }

    @Bean
    ScheduleReader scheduleReader(DataSource source, Clock clock) {
        return new PostgresScheduleReader(source, clock);
    }

    @Bean
    ScheduleQueries scheduleQueries(ScheduleReader reader) { return new ScheduleQueries(reader); }

    @Bean
    ScheduleGrpcEndpoint scheduleGrpcEndpoint(ScheduleQueries queries) { return new ScheduleGrpcEndpoint(queries); }

    @Bean
    @Order(0)
    @GlobalServerInterceptor
    BearerTokenInterceptor scheduleAuthentication(ScheduleProperties properties,
            @Value("${spring.grpc.server.address:127.0.0.1}") InetAddress address) {
        if (!address.isLoopbackAddress() && properties.grpcToken().isBlank()) {
            throw new IllegalArgumentException("SCHEDULE_GRPC_TOKEN is required when binding beyond loopback");
        }
        return new BearerTokenInterceptor(properties.grpcToken());
    }

    @Bean
    @Order(1)
    @GlobalServerInterceptor
    ConcurrentCallsInterceptor scheduleConcurrencyLimit(ScheduleProperties properties) {
        return new ConcurrentCallsInterceptor(properties.grpcMaxConcurrentCalls());
    }

    @Bean
    ArchiveStore archiveStore(ScheduleProperties properties) {
        return new ContentAddressedArchiveStore(properties.archiveDirectory());
    }

    @Bean
    FeedParser feedParser(ScheduleProperties properties) {
        return new StreamingGtfsParser(properties.maxUncompressedBytes());
    }

    @Bean(destroyMethod = "close")
    FeedDownloader feedDownloader(ScheduleProperties properties) {
        var download = properties.download();
        var client = HttpClient.newBuilder().connectTimeout(download.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new HttpFeedDownloader(client, properties.tempDirectory(), download.requestTimeout(), download.attempts(),
                download.initialBackoff(), download.maxRetryDelay(), download.maxBytes());
    }

    @Bean
    ImportFeed importFeed(SchedulePublication publication, ArchiveStore archives, FeedParser parser) {
        return new ImportFeed(publication, archives, parser);
    }

    @Bean(destroyMethod = "close")
    ImportCycleRunner importCycleRunner(ScheduleProperties properties, FeedDownloader downloader,
                                       ImportFeed importer, SchedulePublication publication) {
        var download = properties.download();
        return new ImportCycleRunner(properties.feedConfigs(), downloader, importer, publication,
                download.maxConcurrent(), download.maxPerHost());
    }

    @Bean
    @ConditionalOnProperty(name = "schedule.jobs-enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler scheduleJobExecutor() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("schedule-jobs-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "schedule.jobs-enabled", havingValue = "true", matchIfMissing = true)
    ScheduledImportJobs scheduledImportJobs(ImportCycleRunner runner,
                                           @Qualifier("scheduleJobExecutor") ThreadPoolTaskScheduler scheduler,
                                           ScheduleProperties properties, Clock clock) {
        return new ScheduledImportJobs(runner, scheduler, properties, clock);
    }
}
