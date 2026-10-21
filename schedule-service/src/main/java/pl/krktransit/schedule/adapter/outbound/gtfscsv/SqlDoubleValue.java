package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import java.util.Locale;
import java.util.regex.Pattern;

// Preserve PostgreSQL's Linux strtod conversion rather than Java-only numeric syntax.
final class SqlDoubleValue {
    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern HEXADECIMAL = Pattern.compile(
            "[+-]?0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?");
    private static final Pattern NOT_A_NUMBER = Pattern.compile("[+-]?nan(?:\\([a-z0-9_]*\\))?");

    private SqlDoubleValue() {}

    static double parse(String original) {
        String value = trimSqlWhitespace(original).toLowerCase(Locale.ROOT);
        if (NOT_A_NUMBER.matcher(value).matches()) return Double.NaN;
        if (isPositiveInfinity(value)) return Double.POSITIVE_INFINITY;
        if (isNegativeInfinity(value)) return Double.NEGATIVE_INFINITY;
        return finiteNumber(value);
    }

    private static boolean isPositiveInfinity(String value) {
        return value.equals("inf") || value.equals("+inf") || value.equals("infinity") || value.equals("+infinity");
    }

    private static boolean isNegativeInfinity(String value) {
        return value.equals("-inf") || value.equals("-infinity");
    }

    private static double finiteNumber(String value) {
        boolean hexadecimal = HEXADECIMAL.matcher(value).matches();
        if (!hexadecimal && !DECIMAL.matcher(value).matches()) throw invalid();
        String normalized = hexadecimal && value.indexOf('p') < 0 ? value + "p0" : value;
        double parsed = Double.parseDouble(normalized);
        validateRange(value, parsed, hexadecimal);
        return parsed;
    }

    private static void validateRange(String value, double parsed, boolean hexadecimal) {
        if (!Double.isFinite(parsed) || parsed == 0.0 && hasNonzeroMantissa(value, hexadecimal)) {
            throw new NumberFormatException("SQL double is out of range");
        }
    }

    private static boolean hasNonzeroMantissa(String value, boolean hexadecimal) {
        int exponent = value.indexOf(hexadecimal ? 'p' : 'e');
        int end = exponent < 0 ? value.length() : exponent;
        int start = hexadecimal ? value.indexOf('x') + 1 : 0;
        for (int index = start; index < end; index++) {
            if (Character.digit(value.charAt(index), hexadecimal ? 16 : 10) > 0) return true;
        }
        return false;
    }

    private static String trimSqlWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && sqlWhitespace(value.charAt(start))) start++;
        while (end > start && sqlWhitespace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean sqlWhitespace(char value) {
        return value == ' ' || value >= '\t' && value <= '\r';
    }

    private static NumberFormatException invalid() { return new NumberFormatException("Invalid SQL double input"); }
}
