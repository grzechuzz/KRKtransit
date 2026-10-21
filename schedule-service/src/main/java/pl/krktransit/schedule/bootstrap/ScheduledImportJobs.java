package pl.krktransit.schedule.bootstrap;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import pl.krktransit.schedule.adapter.inbound.scheduling.ImportCycleRunner;

final class ScheduledImportJobs implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledImportJobs.class);
    private final ImportCycleRunner runner;
    private final ThreadPoolTaskScheduler scheduler;
    private final ScheduleProperties properties;
    private final Clock clock;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    ScheduledImportJobs(ImportCycleRunner runner, ThreadPoolTaskScheduler scheduler,
                        ScheduleProperties properties, Clock clock) {
        this.runner = runner;
        this.scheduler = scheduler;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        tasks.add(scheduler.scheduleWithFixedDelay(this::importCycle, clock.instant(), properties.importInterval()));
        tasks.add(scheduler.scheduleWithFixedDelay(this::cleanup,
                clock.instant().plus(properties.cleanupInterval()), properties.cleanupInterval()));
    }

    private void importCycle() {
        LOG.info("Schedule import cycle started");
        LOG.info("Schedule import cycle completed: {}", runner.runCycle());
    }

    private void cleanup() {
        LOG.info("Removed {} expired schedule rows", runner.cleanExpired(properties.cleanupBatchSize()));
    }

    @Override
    public void close() {
        tasks.forEach(task -> task.cancel(true));
    }
}
