package pl.krktransit.schedule.application.querying;

import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class ScheduleQueries {
    private final ScheduleReader reader;

    public ScheduleQueries(ScheduleReader reader) { this.reader = reader; }

    public ScheduleHash getCurrentHash(FeedId feed) { return reader.currentHash(feed); }

    public TripContext getTripContext(String hash, String tripId) {
        return reader.tripContext(new ScheduleHash(hash), requiredId(tripId));
    }

    public ShapeContext getShape(String hash, String shapeId) {
        return reader.shape(new ScheduleHash(hash), requiredId(shapeId));
    }

    private String requiredId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Entity ID is required");
        return id;
    }
}
