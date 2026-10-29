package pl.krktransit.stopevents.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import pl.krktransit.stopevents.adapter.inbound.stream.DetectorLoop;

final class DetectionJobs {
    private static final System.Logger LOG = System.getLogger(DetectionJobs.class.getName());
    private final DetectorLoop loop;
    private final ConfigurableApplicationContext context;

    DetectionJobs(DetectorLoop loop, ConfigurableApplicationContext context) {
        this.loop = loop;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        loop.start(this::crash);
        LOG.log(System.Logger.Level.INFO, "Stop events detector started");
    }

    private void crash(Thread thread, Throwable failure) {
        LOG.log(System.Logger.Level.ERROR, "Detector stopped; operator intervention may be required", failure);
        Thread.ofPlatform().name("stop-events-crash-exit").start(
                () -> System.exit(SpringApplication.exit(context, () -> 1)));
    }
}
