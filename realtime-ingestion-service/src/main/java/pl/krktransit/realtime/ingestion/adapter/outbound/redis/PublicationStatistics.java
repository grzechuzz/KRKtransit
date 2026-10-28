package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class PublicationStatistics {
    enum Outcome { PUBLISHED, DROPPED, UNCONFIRMED }
    private final LongAdder published = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder unconfirmed = new LongAdder();
    private final LongAdder overflowResults = new LongAdder();
    private final LongAdder overflowRecords = new LongAdder();
    private final LongAdder shutdownDiscarded = new LongAdder();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    void record(Outcome outcome, Throwable failure) {
        switch (outcome) {
            case PUBLISHED -> published.increment();
            case DROPPED -> dropped.increment();
            case UNCONFIRMED -> unconfirmed.increment();
        }
        recordFailure(failure);
    }

    private void recordFailure(Throwable failure) {
        if (failure != null) {
            lastFailure.set(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    void overflow(int records) {
        overflowResults.increment();
        overflowRecords.add(records);
    }

    void shutdownDiscarded(int records) {
        shutdownDiscarded.add(records);
    }

    public Snapshot drain() {
        return new Snapshot(published.sumThenReset(), dropped.sumThenReset(), unconfirmed.sumThenReset(),
                overflowResults.sumThenReset(), overflowRecords.sumThenReset(), shutdownDiscarded.sumThenReset(),
                lastFailure.getAndSet(null));
    }

    public record Snapshot(long published, long dropped, long unconfirmed, long overflowResults,
                           long overflowRecords, long shutdownDiscarded, String lastFailure) {
        public boolean hasLoss() {
            return dropped + unconfirmed + overflowRecords + shutdownDiscarded > 0;
        }
    }
}
