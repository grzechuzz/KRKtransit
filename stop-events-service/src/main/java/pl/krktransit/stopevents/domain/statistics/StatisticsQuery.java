package pl.krktransit.stopevents.domain.statistics;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public record StatisticsQuery(String lineNumber, LocalDate startDate, LocalDate endDate, boolean includeEstimated) {
    private static final Pattern LINE_NUMBER = Pattern.compile("[a-zA-Z0-9]{1,5}");
    private static final int MAX_DATE_DIFFERENCE_DAYS = 365;
    private static final Duration SINGLE_DAY_TTL = Duration.ofSeconds(30);
    private static final Duration SHORT_RANGE_TTL = Duration.ofMinutes(1);
    private static final Duration LONG_RANGE_TTL = Duration.ofMinutes(5);
    private static final int WEEK_DAYS = 7;

    public StatisticsQuery {
        requireLine(lineNumber);
        requireDates(startDate, endDate);
    }

    public Duration cacheTtl() {
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days == 1) return SINGLE_DAY_TTL;
        return days < WEEK_DAYS ? SHORT_RANGE_TTL : LONG_RANGE_TTL;
    }

    public void requireNotFuture(LocalDate today) {
        if (endDate.isAfter(today)) throw new InvalidStatisticsQuery("end_date cannot be in the future");
    }

    private static void requireLine(String line) {
        if (!validLine(line)) throw new InvalidStatisticsQuery("line_number must contain 1 to 5 letters or digits");
    }

    private static boolean validLine(String line) { return line != null && LINE_NUMBER.matcher(line).matches(); }

    private static void requireDates(LocalDate start, LocalDate end) {
        if (!datesPresent(start, end)) throw new InvalidStatisticsQuery("start_date and end_date are required");
        if (start.isAfter(end)) throw new InvalidStatisticsQuery("start_date must be <= end_date");
        if (ChronoUnit.DAYS.between(start, end) > MAX_DATE_DIFFERENCE_DAYS) {
            throw new InvalidStatisticsQuery("Date range cannot exceed " + MAX_DATE_DIFFERENCE_DAYS + " days");
        }
    }

    private static boolean datesPresent(LocalDate start, LocalDate end) { return start != null && end != null; }
}
