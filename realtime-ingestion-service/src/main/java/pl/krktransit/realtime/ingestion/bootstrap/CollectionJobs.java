package pl.krktransit.realtime.ingestion.bootstrap;

import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import pl.krktransit.realtime.ingestion.adapter.inbound.scheduling.EndpointPollers;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.PublicationStatistics;
import pl.krktransit.realtime.ingestion.adapter.outbound.redis.RedisObservationSink;

final class CollectionJobs implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CollectionJobs.class);
    private final RealtimeProperties properties;
    private final RedisObservationSink sink;
    private final EndpointPollers pollers;
    private final PublicationStatistics statistics;
    private final ThreadPoolTaskScheduler scheduler;
    private final ConfigurableApplicationContext context;
    private ScheduledFuture<?> report;

    CollectionJobs(RealtimeProperties properties, RedisObservationSink sink, EndpointPollers pollers,
                   PublicationStatistics statistics, ThreadPoolTaskScheduler scheduler,
                   ConfigurableApplicationContext context) {
        this.properties = properties;
        this.sink = sink;
        this.pollers = pollers;
        this.statistics = statistics;
        this.scheduler = scheduler;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        sink.start(this::crash);
        pollers.start(this::crash);
        report = scheduler.scheduleAtFixedRate(this::report, properties.reportInterval());
        LOG.info("Realtime ingestion started for {} endpoints", properties.endpoints().size());
    }

    private void report() {
        var snapshot = statistics.drain();
        if (snapshot.hasLoss()) {
            LOG.warn("Publication: {}, pending {} bytes", snapshot, sink.pendingBytes());
        } else {
            LOG.info("Published {} observations, pending {} bytes", snapshot.published(), sink.pendingBytes());
        }
    }

    private void crash(Thread thread, Throwable failure) {
        LOG.error("{} stopped unexpectedly; shutting down realtime ingestion", thread.getName(), failure);
        Thread.ofPlatform().name("realtime-crash-exit").start(
                () -> System.exit(SpringApplication.exit(context, () -> 1)));
    }

    @Override
    public void close() {
        cancelReport();
        pollers.close();
        sink.close();
        report();
    }

    private void cancelReport() {
        if (report != null) {
            report.cancel(false);
        }
    }
}
