package pl.krktransit.stopevents.domain.lineevents;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;

public record LineEventsQuery(String cityId, String lineNumber, Instant createdAfter, OptionalLong afterId, int pageSize) {
    public static final int DEFAULT_PAGE_SIZE = 2_000;
    public static final int MAXIMUM_PAGE_SIZE = 5_000;
    private static final Pattern CITY_ID = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern LINE_NUMBER = Pattern.compile("[a-zA-Z0-9]{1,5}");

    public LineEventsQuery {
        requireMatch(cityId, CITY_ID, "city_id must be a lowercase city identifier");
        requireMatch(lineNumber, LINE_NUMBER, "line_number must contain 1 to 5 letters or digits");
        if (createdAfter == null) throw new InvalidLineEventsQuery("created_after is required");
        requireAfterId(afterId);
        requirePageSize(pageSize);
    }

    private static void requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) throw new InvalidLineEventsQuery(message);
    }

    private static void requireAfterId(OptionalLong afterId) {
        if (afterId == null) throw new IllegalArgumentException("afterId must be present or empty");
        if (afterId.isPresent() && afterId.getAsLong() < 0) throw new InvalidLineEventsQuery("after_id cannot be negative");
    }

    private static void requirePageSize(int pageSize) {
        if (!validPageSize(pageSize)) {
            throw new InvalidLineEventsQuery("page_size must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
    }

    private static boolean validPageSize(int pageSize) { return pageSize >= 1 && pageSize <= MAXIMUM_PAGE_SIZE; }
}
