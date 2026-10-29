package pl.krktransit.livevehicles.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import pl.krktransit.livevehicles.adapter.inbound.stream.ProjectionLoop;

final class ProjectionJobs {
    private static final System.Logger LOG = System.getLogger(ProjectionJobs.class.getName());
    private final ProjectionLoop loop;
    private final ConfigurableApplicationContext context;

    ProjectionJobs(ProjectionLoop loop, ConfigurableApplicationContext context) { this.loop = loop; this.context = context; }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        loop.start(this::crash);
        LOG.log(System.Logger.Level.INFO, "Live vehicles projector started");
    }

    private void crash(Thread thread, Throwable failure) {
        LOG.log(System.Logger.Level.ERROR, "Live vehicles projector stopped unexpectedly", failure);
        Thread.ofPlatform().name("live-vehicles-crash-exit").start(() -> System.exit(SpringApplication.exit(context, () -> 1)));
    }
}
