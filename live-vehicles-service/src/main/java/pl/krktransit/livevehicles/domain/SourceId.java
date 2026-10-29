package pl.krktransit.livevehicles.domain;

public record SourceId(String cityId, String sourceId) {
    public SourceId {
        if (!valid(cityId) || !valid(sourceId)) throw new IllegalArgumentException("Invalid source identity");
    }

    private static boolean valid(String value) { return value != null && value.matches("[a-z0-9][a-z0-9-]*"); }

    public String scope(String originalId) { return this + ":" + originalId; }

    @Override
    public String toString() { return cityId + ":" + sourceId; }
}
