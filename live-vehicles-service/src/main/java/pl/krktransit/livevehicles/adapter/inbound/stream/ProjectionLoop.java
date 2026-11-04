package pl.krktransit.livevehicles.adapter.inbound.stream;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.krktransit.livevehicles.application.ObservationFailure;
import pl.krktransit.livevehicles.application.ObservationSource;
import pl.krktransit.livevehicles.application.TripForecasts;
import pl.krktransit.livevehicles.application.VehiclePositions;
import pl.krktransit.livevehicles.domain.SourceId;

public final class ProjectionLoop implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProjectionLoop.class.getName());
    private final ObservationSource source;
    private final VehiclePositions positions;
    private final TripForecasts forecasts;
    private final Set<SourceId> sources;
    private final Duration retention;
    private final Duration retryDelay;
    private final Duration shutdownTimeout;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public ProjectionLoop(ObservationSource source, VehiclePositions positions, TripForecasts forecasts, Set<SourceId> sources,
                          Duration retention, Duration retryDelay, Duration shutdownTimeout, Clock clock) {
        this.source = source;
        this.positions = positions;
        this.forecasts = forecasts;
        this.sources = Set.copyOf(sources);
        this.retention = retention;
        this.retryDelay = retryDelay;
        this.shutdownTimeout = shutdownTimeout;
        this.clock = clock;
    }

    public synchronized void start(Thread.UncaughtExceptionHandler failureHandler) {
        if (!running.compareAndSet(false, true)) return;
        worker = Thread.ofPlatform().name("live-vehicles-projector").uncaughtExceptionHandler(failureHandler).start(this::run);
    }

    private void run() {
        Map<SourceId, String> cursors = null;
        boolean failed = false;
        while (running.get()) {
            try {
                cursors = readAndProject(cursors);
                reportRecovery(failed);
                failed = false;
            } catch (ObservationFailure failure) {
                if (!running.get()) return;
                reportPause(failed, failure);
                failed = true;
                cursors = null;
                if (!pause()) return;
            }
        }
    }

    private Map<SourceId, String> readAndProject(Map<SourceId, String> cursors) {
        var batch = source.read(resumeFrom(cursors));
        project(batch);
        return batch.cursors();
    }

    private Map<SourceId, String> resumeFrom(Map<SourceId, String> cursors) {
        if (cursors != null) return cursors;
        return source.recentCursors(sources, retention);
    }

    private void project(ObservationSource.ReadBatch batch) {
        for (var position : batch.positions()) positions.accept(position, clock.instant());
        for (var forecast : batch.forecasts()) forecasts.accept(forecast, clock.instant());
    }

    private void reportRecovery(boolean failed) {
        if (failed) LOG.log(System.Logger.Level.INFO, "Live observation Redis recovered");
    }

    private void reportPause(boolean failed, ObservationFailure failure) {
        if (!failed) LOG.log(System.Logger.Level.WARNING, "Live projection paused; retrying Redis", failure);
    }

    private boolean pause() {
        try { Thread.sleep(retryDelay); return running.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (!workerNeedsJoining()) return;
        worker.interrupt();
        awaitShutdown();
    }

    private boolean workerNeedsJoining() { return worker != null && worker != Thread.currentThread(); }

    private void awaitShutdown() {
        try {
            if (!worker.join(shutdownTimeout)) LOG.log(System.Logger.Level.WARNING, "Live projector shutdown timed out");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
