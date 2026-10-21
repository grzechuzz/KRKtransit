package pl.krktransit.schedule.domain.schedule;

public final class GtfsTime {
    private GtfsTime() {}

    public static int parse(String value) {
        String[] parts = split(value);
        validateFormat(parts, value);
        return toSeconds(parts, value);
    }

    private static String[] split(String value) {
        if (value == null) throw new IllegalArgumentException("GTFS time is missing");
        return value.strip().split(":", -1);
    }

    private static void validateFormat(String[] parts, String value) {
        if (parts.length != 3 || parts[1].length() != 2 || parts[2].length() != 2) {
            throw invalid(value);
        }
        for (String part : parts) {
            if (part.isEmpty() || !part.codePoints().allMatch(Character::isDigit)) throw invalid(value);
        }
    }

    private static int toSeconds(String[] parts, String value) {
        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);
            if (minutes > 59 || seconds > 59) throw invalid(value);
            return Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600), minutes * 60), seconds);
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException("GTFS time exceeds SQL integer range: " + value, failure);
        }
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("Invalid GTFS time: " + value);
    }
}
