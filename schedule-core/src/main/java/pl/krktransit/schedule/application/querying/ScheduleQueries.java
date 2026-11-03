package pl.krktransit.schedule.application.querying;

import java.util.List;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.lineroute.LineDirection;
import pl.krktransit.schedule.domain.lineroute.LineDirections;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class ScheduleQueries {
    private final ScheduleReader reader;

    public ScheduleQueries(ScheduleReader reader) { this.reader = reader; }

    public ScheduleHash getCurrentHash(FeedId feed) { return reader.currentHash(feed); }

    public List<LineDirection> getLineRoutes(String lineNumber) {
        if (lineNumber == null || !lineNumber.matches("[a-zA-Z0-9]{1,5}")) {
            throw new IllegalArgumentException("line_number must contain 1 to 5 letters or digits");
        }
        return LineDirections.from(reader.lineRouteVariants(lineNumber));
    }

    public TripContext getTripContext(String hash, String tripId) {
        return reader.tripContext(new ScheduleHash(hash), requiredId(tripId));
    }

    public ShapeContext getShape(String hash, String shapeId) {
        return reader.shape(new ScheduleHash(hash), requiredId(shapeId));
    }

    private String requiredId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Entity ID is required");
        if (id.indexOf('\0') >= 0) throw new IllegalArgumentException("Entity ID must not contain NUL");
        return id;
    }
}
