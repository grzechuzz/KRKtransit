package pl.krktransit.realtime.ingestion.domain.source;

public record SourceId(String cityId, String sourceId) {
    public SourceId {
        requirePart(cityId, "cityId");
        requirePart(sourceId, "sourceId");
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
