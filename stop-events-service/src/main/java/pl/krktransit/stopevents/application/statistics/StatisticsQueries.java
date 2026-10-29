package pl.krktransit.stopevents.application.statistics;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

public final class StatisticsQueries implements AutoCloseable {
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private final StatisticsReader reader;
    private final StatisticsCache cache;
    private final Clock clock;
    private final ExecutorService workers;
    private final Semaphore permits;
    private final Map<Key, Flight> flights = new HashMap<>();
    private boolean closed;

    public StatisticsQueries(StatisticsReader reader, StatisticsCache cache, Clock clock,
                             ExecutorService workers, int maximumQueries) {
        if (maximumQueries < 1) throw new IllegalArgumentException("Maximum statistics queries must be positive");
        this.reader = reader;
        this.cache = cache;
        this.clock = clock;
        this.workers = workers;
        permits = new Semaphore(maximumQueries);
    }

    public StatisticsResult get(Statistic statistic, StatisticsQuery query, QueryCancellation cancellation) {
        query.requireNotFuture(LocalDate.now(clock.withZone(ZONE)));
        cancellation.check();
        var cached = cache.get(statistic, query);
        cancellation.check();
        if (cached.isPresent()) return cached.get();
        var flight = join(new Key(statistic, query));
        try { return await(flight, cancellation); }
        finally { leave(flight); }
    }

    private synchronized Flight join(Key key) {
        if (closed) throw new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Statistics service is stopping");
        var current = flights.get(key);
        if (current != null) { current.waiters++; return current; }
        if (!permits.tryAcquire()) throw new StatisticsFailure(StatisticsFailure.Kind.BUSY, "Statistics query capacity reached");
        var flight = new Flight(key);
        flights.put(key, flight);
        try { workers.execute(flight); }
        catch (RejectedExecutionException failure) {
            flights.remove(key, flight);
            permits.release();
            throw new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Statistics service is stopping", failure);
        }
        return flight;
    }

    private StatisticsResult await(Flight flight, QueryCancellation cancellation) {
        var thread = Thread.currentThread();
        try (var registration = cancellation.onCancel(thread::interrupt)) {
            cancellation.check();
            return flight.task.get();
        } catch (InterruptedException interrupted) {
            thread.interrupt();
            throw new StatisticsFailure(StatisticsFailure.Kind.CANCELLED, "Statistics request cancelled", interrupted);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new StatisticsFailure(StatisticsFailure.Kind.CANCELLED, "Statistics query cancelled", cancelled);
        } catch (ExecutionException failure) { throw queryFailure(failure.getCause()); }
    }

    private RuntimeException queryFailure(Throwable cause) {
        if (cause instanceof RuntimeException failure) return failure;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Unexpected statistics query failure", cause);
    }

    private void leave(Flight flight) {
        synchronized (this) {
            flight.waiters--;
            if (!unneeded(flight)) return;
            flights.remove(flight.key, flight);
        }
        flight.cancel();
    }

    private boolean unneeded(Flight flight) { return flight.waiters == 0 && !flight.task.isDone(); }

    private StatisticsResult load(Key key, QueryCancellation cancellation) {
        cancellation.check();
        var cached = cache.get(key.statistic(), key.query());
        if (cached.isPresent()) return cached.get();
        var result = reader.read(key.statistic(), key.query(), cancellation);
        cancellation.check();
        cache.put(key.statistic(), key.query(), result);
        return result;
    }

    @Override
    public void close() {
        stopFlights().forEach(Flight::cancel);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private synchronized List<Flight> stopFlights() {
        closed = true;
        return List.copyOf(flights.values());
    }

    private record Key(Statistic statistic, StatisticsQuery query) {}

    private final class Flight implements Runnable {
        private final Key key;
        private final QueryCancellation cancellation = new QueryCancellation();
        private final FutureTask<StatisticsResult> task;
        private int waiters = 1;

        private Flight(Key key) {
            this.key = key;
            task = new FutureTask<>(() -> load(key, cancellation));
        }

        @Override
        public void run() {
            try { task.run(); }
            finally {
                synchronized (StatisticsQueries.this) { flights.remove(key, this); }
                permits.release();
            }
        }

        private void cancel() {
            cancellation.cancel();
            task.cancel(true);
        }
    }
}
