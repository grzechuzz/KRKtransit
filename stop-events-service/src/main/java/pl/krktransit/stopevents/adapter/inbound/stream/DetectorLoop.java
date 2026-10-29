package pl.krktransit.stopevents.adapter.inbound.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.krktransit.stopevents.application.port.DetectorOwnership;
import pl.krktransit.stopevents.application.port.DetectorStateStore;
import pl.krktransit.stopevents.application.port.ObservationSource;
import pl.krktransit.stopevents.application.port.StopEventStore;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.application.processing.BatchOutcome;
import pl.krktransit.stopevents.application.processing.ProcessObservationBatch;
import pl.krktransit.stopevents.domain.source.SourceId;

public final class DetectorLoop implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DetectorLoop.class.getName());
    private final DetectorOwnership ownership;
    private final ObservationSource input;
    private final DetectorStateStore state;
    private final StopEventStore events;
    private final ProcessObservationBatch process;
    private final Set<SourceId> sources;
    private final List<Duration> failureDelays;
    private final Duration shutdownTimeout;
    private final Clock clock;
    private final Map<SourceId, String> cursors = new LinkedHashMap<>();
    private final Map<SourceId, Instant> pausedUntil = new HashMap<>();
    private final Map<SourceId, Integer> scheduleFailures = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;
    private int storageFailures;
    private boolean reload;

    public DetectorLoop(DetectorOwnership ownership, ObservationSource input, DetectorStateStore state,
                        StopEventStore events, ProcessObservationBatch process, Set<SourceId> sources,
                        List<Duration> failureDelays, Duration shutdownTimeout, Clock clock) {
        this.ownership = ownership;
        this.input = input;
        this.state = state;
        this.events = events;
        this.process = process;
        this.sources = Set.copyOf(sources);
        this.failureDelays = List.copyOf(failureDelays);
        this.shutdownTimeout = shutdownTimeout;
        this.clock = clock;
    }

    public void start(Thread.UncaughtExceptionHandler failureHandler) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Detector is already started");
        initialize();
        thread = Thread.ofPlatform().name("stop-events-detector").uncaughtExceptionHandler(failureHandler).start(this::run);
    }

    private void initialize() {
        ownership.hold();
        state.checkAvailable();
        events.checkAvailable();
        cursors.putAll(state.cursors(sources));
        sources.stream().sorted(Comparator.comparing(SourceId::toString)).forEach(source ->
                cursors.computeIfAbsent(source, input::tail));
    }

    private void run() {
        while (running.get()) {
            try {
                cycle();
                storageFailures = 0;
            } catch (StorageFailure failure) {
                process.requireRecovery(sources);
                reload = true;
                LOG.log(System.Logger.Level.WARNING, "Detector storage unavailable; retained input will be retried", failure);
                pause(delay(storageFailures++));
            }
        }
    }

    private void cycle() {
        ownership.hold();
        if (reload) reloadCursors();
        var active = activeCursors();
        if (active.isEmpty()) { pause(Duration.ofMillis(100)); return; }
        var batches = input.read(active);
        if (batches.isEmpty() || !running.get()) return;
        accept(process.process(batches));
    }

    private void reloadCursors() {
        state.checkAvailable();
        cursors.putAll(state.cursors(sources));
        reload = false;
    }

    private Map<SourceId, String> activeCursors() {
        var active = new LinkedHashMap<SourceId, String>();
        cursors.forEach((source, cursor) -> { if (!paused(source)) active.put(source, cursor); });
        return active;
    }

    private boolean paused(SourceId source) {
        var deadline = pausedUntil.get(source);
        return deadline != null && clock.instant().isBefore(deadline);
    }

    private void accept(BatchOutcome outcome) {
        cursors.putAll(outcome.cursors());
        outcome.cursors().keySet().forEach(source -> {
            scheduleFailures.remove(source);
            pausedUntil.remove(source);
        });
        outcome.pausedSources().forEach(this::pauseSource);
        if (outcome.eventCount() > 0) {
            LOG.log(System.Logger.Level.INFO, "Stored {0} stop events", outcome.eventCount());
        }
    }

    private void pauseSource(SourceId source) {
        int failures = scheduleFailures.getOrDefault(source, 0);
        pausedUntil.put(source, clock.instant().plus(delay(failures)));
        scheduleFailures.put(source, Math.min(failures + 1, failureDelays.size() - 1));
    }

    private Duration delay(int failures) { return failureDelays.get(Math.min(failures, failureDelays.size() - 1)); }

    private void pause(Duration duration) {
        try { Thread.sleep(duration); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); running.set(false); }
    }

    @Override
    public void close() {
        running.set(false);
        if (thread == null) return;
        try {
            if (!thread.join(shutdownTimeout)) {
                thread.interrupt();
                if (!thread.join(shutdownTimeout)) throw new IllegalStateException("Detector did not finish shutdown");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
