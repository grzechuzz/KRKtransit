package pl.krktransit.livevehicles.domain;

public record SourceId(String cityId, String sourceId) {
    public SourceId {
        requirePart(cityId);
        requirePart(sourceId);
    }

    private static void requirePart(String value) {
        if (!validPart(value)) throw new IllegalArgumentException("Invalid source identity");
    }

    private static boolean validPart(String value) { return value != null && value.matches("[a-z0-9][a-z0-9-]*"); }

    public String scope(String originalId) { return this + ":" + originalId; }

    @Override
    public String toString() { return cityId + ":" + sourceId; }
}
