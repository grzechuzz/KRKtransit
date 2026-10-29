package pl.krktransit.stopevents.application.statistics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueryCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> callbacks = ConcurrentHashMap.newKeySet();

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        callbacks.forEach(this::invoke);
    }

    public Registration onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get()) invoke(callback);
        return () -> callbacks.remove(callback);
    }

    public void check() {
        if (cancelled.get()) throw new StatisticsFailure(StatisticsFailure.Kind.CANCELLED, "Statistics request cancelled");
    }

    private void invoke(Runnable callback) {
        if (callbacks.remove(callback)) callback.run();
    }

    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
