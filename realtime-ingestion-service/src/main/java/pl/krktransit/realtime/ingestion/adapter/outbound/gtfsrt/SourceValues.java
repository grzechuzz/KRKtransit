package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

final class SourceValues {
    private SourceValues() {}

    static String text(boolean present, String value) {
        return present && !value.isBlank() ? value : null;
    }

    static Long posixSeconds(boolean present, long value) {
        return present && value > 0 ? value : null;
    }

    static boolean isCoordinate(float value, float limit) {
        return Float.isFinite(value) && Math.abs(value) <= limit;
    }
}
