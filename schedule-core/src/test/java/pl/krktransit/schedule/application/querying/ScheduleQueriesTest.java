package pl.krktransit.schedule.application.querying;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.lineroute.RouteVariant;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

class ScheduleQueriesTest {
    @Test
    void validatesLineBeforeReadingAndReturnsEmptyDirectionsForAnUnknownLine() {
        var reader = new Reader();
        var queries = new ScheduleQueries(reader);
        assertThrows(IllegalArgumentException.class, () -> queries.getLineRoutes(null));
        assertThrows(IllegalArgumentException.class, () -> queries.getLineRoutes("123456"));
        assertNull(reader.line);
        assertTrue(queries.getLineRoutes("198").isEmpty());
        assertEquals("198", reader.line);
    }

    private static final class Reader implements ScheduleReader {
        private String line;
        @Override public List<RouteVariant> lineRouteVariants(String lineNumber) { line = lineNumber; return List.of(); }
        @Override public ScheduleHash currentHash(FeedId feed) { throw new AssertionError(); }
        @Override public TripContext tripContext(ScheduleHash hash, String trip) { throw new AssertionError(); }
        @Override public ShapeContext shape(ScheduleHash hash, String shape) { throw new AssertionError(); }
    }
}
