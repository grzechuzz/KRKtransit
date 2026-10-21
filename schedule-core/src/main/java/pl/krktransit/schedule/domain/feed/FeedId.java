package pl.krktransit.schedule.domain.feed;

import java.util.Objects;

public record FeedId(String cityId, String sourceId) {
    public FeedId {
        requirePart(cityId, "cityId");
        requirePart(sourceId, "sourceId");
    }

    public String scope(String originalId) {
        return cityId + ":" + sourceId + ":" + Objects.requireNonNull(originalId);
    }

    @Override
    public String toString() {
        return cityId + ":" + sourceId;
    }

    private static void requirePart(String value, String name) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException(name + " must be a lowercase namespace component");
        }
    }
}
