package pl.krktransit.stopevents.domain.source;

public record SourceId(String cityId, String sourceId) {
    public SourceId {
        requirePart(cityId);
        requirePart(sourceId);
    }

    private static void requirePart(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid source identity");
        }
    }

    public String scope(String originalId) {
        if (originalId == null || originalId.isBlank()) {
            throw new IllegalArgumentException("An original entity ID is required");
        }
        return this + ":" + originalId;
    }

    @Override
    public String toString() { return cityId + ":" + sourceId; }
}
