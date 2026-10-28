package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import pl.krktransit.realtime.ingestion.application.port.ObservationSink;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;

public final class RedisObservationSink implements ObservationSink, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RedisObservationSink.class.getName());
    private final StreamRoutes routes;
    private final Supplier<StreamWriter> writers;
    private final PublicationBuffer buffer;
    private final PublicationStatistics statistics;
    private final int maxInFlight;
    private final Semaphore inFlight;
    private final Duration recoveryDelay;
    private final Duration shutdownDrain;
    private final ObservationWireMapper mapper = new ObservationWireMapper();
    private final AtomicReference<StreamWriter> failedWriter = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread publisher;
    private volatile StreamWriter writer;

    public RedisObservationSink(StreamRoutes routes, RedisClients clients, long bufferBytes, int maxInFlight,
                                Duration recoveryDelay, Duration shutdownDrain, PublicationStatistics statistics) {
        this(routes, clients::connect, bufferBytes, maxInFlight, recoveryDelay, shutdownDrain, statistics);
    }

    RedisObservationSink(StreamRoutes routes, Supplier<StreamWriter> writers, long bufferBytes, int maxInFlight,
                         Duration recoveryDelay, Duration shutdownDrain, PublicationStatistics statistics) {
        validateLimits(maxInFlight, recoveryDelay, shutdownDrain);
        this.routes = routes;
        this.writers = writers;
        this.buffer = new PublicationBuffer(bufferBytes);
        this.statistics = statistics;
        this.maxInFlight = maxInFlight;
        this.inFlight = new Semaphore(maxInFlight);
        this.recoveryDelay = recoveryDelay;
        this.shutdownDrain = shutdownDrain;
        this.publisher = Thread.ofPlatform().name("realtime-redis-publisher").unstarted(this::publishPending);
    }

    private static void validateLimits(int maxInFlight, Duration recoveryDelay, Duration shutdownDrain) {
        if (!hasValidInFlightLimit(maxInFlight)) {
            throw new IllegalArgumentException("Redis in-flight limit must be positive");
        }
        if (!hasValidDurations(recoveryDelay, shutdownDrain)) {
            throw new IllegalArgumentException("Publication durations must not be negative");
        }
    }

    private static boolean hasValidInFlightLimit(int maxInFlight) {
        return maxInFlight > 0;
    }

    private static boolean hasValidDurations(Duration recoveryDelay, Duration shutdownDrain) {
        return !recoveryDelay.isNegative() && !shutdownDrain.isNegative();
    }

    public void start(Thread.UncaughtExceptionHandler crashHandler) {
        writer = writers.get();
        publisher.setUncaughtExceptionHandler(crashHandler);
        publisher.start();
    }

    @Override
    public void awaitCapacity() throws InterruptedException {
        buffer.awaitRoom();
    }

    @Override
    public boolean offer(List<RealtimeObservation> observations) {
        List<PendingRecord> records = observations.stream().map(this::pending).toList();
        if (buffer.offer(records)) {
            return true;
        }
        statistics.overflow(records.size());
        return false;
    }

    private PendingRecord pending(RealtimeObservation observation) {
        return new PendingRecord(routes.target(observation.origin().source()), mapper.encode(observation));
    }

    public long pendingBytes() {
        return buffer.pendingBytes();
    }

    private void publishPending() {
        try {
            PendingRecord record;
            while ((record = nextRecord()) != null) {
                publishWhenReady(record);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            awaitInFlight(Thread.currentThread().isInterrupted() ? Duration.ZERO : shutdownDrain);
            closeWriter();
        }
    }

    private PendingRecord nextRecord() throws InterruptedException {
        return Thread.currentThread().isInterrupted() ? null : buffer.take();
    }

    private void publishWhenReady(PendingRecord record) throws InterruptedException {
        boolean admitted = false;
        try {
            inFlight.acquire();
            admitted = true;
            awaitWriter();
        } catch (InterruptedException failure) {
            if (admitted) {
                inFlight.release();
            }
            discardAtShutdown(record);
            throw failure;
        }
        publish(record);
    }

    private void discardAtShutdown(PendingRecord record) {
        buffer.release(record);
        statistics.shutdownDiscarded(1);
    }

    private void awaitWriter() throws InterruptedException {
        if (!needsReconnect()) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Redis connection failed; reconnecting");
        int attempts = 0;
        while (needsReconnect()) {
            attempts++;
            reconnect(attempts);
        }
        LOG.log(System.Logger.Level.INFO, "Reconnected to Redis after {0} attempts", attempts);
    }

    private boolean needsReconnect() {
        var current = writer;
        return current == null || !current.isOpen() || failedWriter.get() == current;
    }

    private void reconnect(int attempt) throws InterruptedException {
        closeWriter();
        try {
            writer = writers.get();
        } catch (RedisUnavailable failure) {
            if (attempt == 1) {
                LOG.log(System.Logger.Level.WARNING, "Redis is unavailable; retrying every " + recoveryDelay, failure);
            }
            Thread.sleep(recoveryDelay);
        }
    }

    private void publish(PendingRecord record) {
        var sentOn = writer;
        var append = sentOn.append(record.target(), record.payload()).toCompletableFuture();
        if (append.isCompletedExceptionally()) {
            handleRejectedAppend(record, sentOn, append);
        } else {
            append.whenComplete((id, failure) -> completed(record, sentOn, failure));
        }
    }

    private void handleRejectedAppend(PendingRecord record, StreamWriter sentOn, CompletableFuture<String> append) {
        failedWriter.set(sentOn);
        append.whenComplete((id, failure) -> complete(record, PublicationStatistics.Outcome.DROPPED, failure));
    }

    private void completed(PendingRecord record, StreamWriter sentOn, Throwable failure) {
        if (PublicationOutcomes.requiresReconnect(failure)) {
            failedWriter.set(sentOn);
        }
        complete(record, PublicationOutcomes.afterDispatch(failure), failure);
    }

    private void complete(PendingRecord record, PublicationStatistics.Outcome outcome, Throwable failure) {
        buffer.release(record);
        statistics.record(outcome, failure);
        inFlight.release();
    }

    private void awaitInFlight(Duration timeout) {
        boolean interrupted = Thread.interrupted();
        try {
            if (inFlight.tryAcquire(maxInFlight, timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                inFlight.release(maxInFlight);
            }
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeWriter() {
        if (writer == null) {
            return;
        }
        writer.close();
        writer = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        buffer.close();
        if (publisher.isAlive()) {
            stopPublisher();
        }
        discardQueued();
    }

    private void discardQueued() {
        int discarded = buffer.discardQueued();
        statistics.shutdownDiscarded(discarded);
        if (discarded > 0) {
            LOG.log(System.Logger.Level.WARNING, "Discarded {0} unpublished observations at shutdown", discarded);
        }
    }

    private void stopPublisher() {
        boolean interrupted = Thread.interrupted();
        try {
            publisher.join(shutdownDrain);
            if (publisher.isAlive()) {
                publisher.interrupt();
            }
            publisher.join(shutdownDrain);
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
