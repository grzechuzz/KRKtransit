package pl.krktransit.stopevents.application.lineevents;

import java.util.concurrent.Semaphore;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

public final class LineEvents {
    private final LineEventReader reader;
    private final Semaphore permits;

    public LineEvents(LineEventReader reader, int maximumReads) {
        if (maximumReads < 1) throw new IllegalArgumentException("Maximum line event reads must be positive");
        this.reader = reader;
        permits = new Semaphore(maximumReads);
    }

    public LineEventsPage list(LineEventsQuery query) {
        if (!permits.tryAcquire()) throw new LineEventsFailure(LineEventsFailure.Kind.BUSY, "Line event read capacity reached");
        try { return reader.read(query); }
        finally { permits.release(); }
    }
}
