package pl.krktransit.realtime.ingestion.adapter.inbound.scheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import pl.krktransit.realtime.ingestion.application.collection.CollectEndpoint;
import pl.krktransit.realtime.ingestion.application.collection.EndpointBackoff;
import pl.krktransit.realtime.ingestion.application.collection.EntityRejection;
import pl.krktransit.realtime.ingestion.application.collection.FetchFailure;
import pl.krktransit.realtime.ingestion.application.collection.PollResult;
import pl.krktransit.realtime.ingestion.application.port.ObservationSink;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public final class EndpointPollers implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(EndpointPollers.class.getName());
    private final List<Endpoint> endpoints;
    private final CollectEndpoint collect;
    private final ObservationSink sink;
    private final Supplier<EndpointBackoff> backoffs;
    private final Sleeper sleeper;
    private final Duration shutdownTimeout;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public EndpointPollers(List<Endpoint> endpoints, CollectEndpoint collect, ObservationSink sink,
                           Supplier<EndpointBackoff> backoffs, Duration shutdownTimeout) {
        this(endpoints, collect, sink, backoffs, shutdownTimeout, Thread::sleep);
    }

    EndpointPollers(List<Endpoint> endpoints, CollectEndpoint collect, ObservationSink sink,
                    Supplier<EndpointBackoff> backoffs, Duration shutdownTimeout, Sleeper sleeper) {
        if (!hasUniqueEndpoints(endpoints)) {
            throw new IllegalArgumentException("Each realtime endpoint must be unique");
        }
        this.endpoints = List.copyOf(endpoints);
        this.collect = collect;
        this.sink = sink;
        this.backoffs = backoffs;
        this.shutdownTimeout = shutdownTimeout;
        this.sleeper = sleeper;
    }

    private static boolean hasUniqueEndpoints(List<Endpoint> endpoints) {
        return endpoints.stream().distinct().count() == endpoints.size();
    }

    public synchronized void start(Thread.UncaughtExceptionHandler crashHandler) {
        if (!canStart()) {
            throw new IllegalStateException("Endpoint pollers cannot be restarted");
        }
        for (Endpoint endpoint : endpoints) {
            workers.add(startWorker(endpoint, crashHandler));
        }
    }

    private boolean canStart() {
        return !closed.get() && workers.isEmpty();
    }

    private Thread startWorker(Endpoint endpoint, Thread.UncaughtExceptionHandler crashHandler) {
        return Thread.ofVirtual().name("realtime-poll-" + endpoint).uncaughtExceptionHandler(crashHandler)
                .start(() -> pollUntilStopped(endpoint, backoffs.get()));
    }

    private void pollUntilStopped(Endpoint endpoint, EndpointBackoff backoff) {
        Set<EntityRejection> reportedRejections = Set.of();
        try {
            while (isRunning()) {
                reportedRejections = pollOnce(endpoint, backoff, reportedRejections);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (FetchFailure failure) {
            if (failure.kind() != FetchFailure.Kind.CANCELLED) {
                throw failure;
            }
        }
    }

    private boolean isRunning() {
        return !closed.get() && !Thread.currentThread().isInterrupted();
    }

    private Set<EntityRejection> pollOnce(Endpoint endpoint, EndpointBackoff backoff,
                                          Set<EntityRejection> reportedRejections) throws InterruptedException {
        sink.awaitCapacity();
        if (!isRunning()) {
            return reportedRejections;
        }
        int previousFailures = backoff.consecutiveFailures();
        PollResult result = collect.collect(endpoint);
        Duration delay = backoff.next(result);
        report(endpoint, result, previousFailures, backoff.consecutiveFailures(), delay);
        var rejections = reportRejectionChange(endpoint, result, reportedRejections);
        sleeper.sleep(delay);
        return rejections;
    }

    private static Set<EntityRejection> reportRejectionChange(Endpoint endpoint, PollResult result,
                                                              Set<EntityRejection> reported) {
        if (!isNewRejectionSet(result, reported)) {
            return reported;
        }
        LOG.log(System.Logger.Level.INFO, "{0}: rejected entities {1}", endpoint, result.rejections());
        return Set.copyOf(result.rejections().keySet());
    }

    private static boolean isNewRejectionSet(PollResult result, Set<EntityRejection> reported) {
        return !result.rejections().isEmpty() && !result.rejections().keySet().equals(reported);
    }

    private static void report(Endpoint endpoint, PollResult result, int previousFailures, int failures,
                               Duration delay) {
        if (result.providerFailure()) {
            reportProviderFailure(endpoint, result, failures, delay);
            return;
        }
        if (isOverflow(result)) {
            reportOverflow(endpoint, result);
            return;
        }
        reportSuccess(endpoint, result, previousFailures);
    }

    private static void reportProviderFailure(Endpoint endpoint, PollResult result, int failures, Duration delay) {
        var kind = result.fetchFailure() != null ? result.fetchFailure() : result.outcome();
        LOG.log(System.Logger.Level.WARNING, "{0}: {1} {2} (failure {3}, next attempt in {4})", endpoint,
                kind, result.detail(), failures, delay);
    }

    private static boolean isOverflow(PollResult result) {
        return result.outcome() == PollResult.Outcome.OVERFLOW;
    }

    private static void reportOverflow(Endpoint endpoint, PollResult result) {
        LOG.log(System.Logger.Level.WARNING, "{0}: discarded {1} observations, publication buffer is full",
                endpoint, result.observations());
    }

    private static void reportSuccess(Endpoint endpoint, PollResult result, int previousFailures) {
        if (isUnchanged(result)) {
            LOG.log(System.Logger.Level.DEBUG, "{0}: not modified", endpoint);
            return;
        }
        if (isRecovery(previousFailures)) {
            LOG.log(System.Logger.Level.INFO, "{0}: recovered after {1} failures, {2} observations", endpoint,
                    previousFailures, result.observations());
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "{0}: {1} observations", endpoint, result.observations());
    }

    private static boolean isUnchanged(PollResult result) {
        return result.outcome() == PollResult.Outcome.UNCHANGED;
    }

    private static boolean isRecovery(int previousFailures) {
        return previousFailures > 0;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        workers.forEach(Thread::interrupt);
        awaitWorkers();
    }

    private void awaitWorkers() {
        boolean interrupted = Thread.interrupted();
        try {
            for (Thread worker : workers) {
                awaitWorker(worker);
            }
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitWorker(Thread worker) throws InterruptedException {
        if (!worker.join(shutdownTimeout)) {
            LOG.log(System.Logger.Level.WARNING, "{0} has not stopped within {1}", worker.getName(), shutdownTimeout);
        }
    }

    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
}
