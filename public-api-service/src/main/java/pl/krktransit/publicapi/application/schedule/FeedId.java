package pl.krktransit.publicapi.application.schedule;

import java.util.Arrays;
import java.util.Optional;

public record FeedId(String cityId, String sourceId) {
    private static final int SCOPED_ID_PARTS = 3;

    public FeedId {
        if (!hasText(cityId) || !hasText(sourceId)) throw new IllegalArgumentException("City and source IDs are required");
    }

    public static Optional<FeedId> ofScopedId(String scopedId) {
        var parts = scopedId.split(":", SCOPED_ID_PARTS);
        return isScoped(parts) ? Optional.of(new FeedId(parts[0], parts[1])) : Optional.empty();
    }

    private static boolean isScoped(String[] parts) {
        return parts.length == SCOPED_ID_PARTS && Arrays.stream(parts).allMatch(FeedId::hasText);
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
