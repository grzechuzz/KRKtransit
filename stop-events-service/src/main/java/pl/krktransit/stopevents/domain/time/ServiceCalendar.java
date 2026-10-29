package pl.krktransit.stopevents.domain.time;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ServiceCalendar {
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    public LocalDate serviceDate(Instant timestamp, int plannedSeconds) {
        var local = timestamp.atZone(ZONE).toLocalDate();
        return Stream.of(local.minusDays(1), local, local.plusDays(1))
                .min(Comparator.comparing(date -> distance(timestamp, date, plannedSeconds))).orElseThrow();
    }

    public LocalDate earliestServiceDate(Instant timestamp) {
        return timestamp.atZone(ZONE).toLocalDate().minusDays(1);
    }

    private Duration distance(Instant timestamp, LocalDate date, int plannedSeconds) {
        return Duration.between(plannedTime(date, plannedSeconds), timestamp).abs();
    }

    public Instant plannedTime(LocalDate date, int plannedSeconds) {
        return date.atStartOfDay().plusDays(plannedSeconds / 86400L)
                .plusSeconds(plannedSeconds % 86400L).atZone(ZONE).toInstant();
    }

    public int delay(Instant eventTime, Instant plannedTime) {
        var difference = Duration.between(plannedTime, eventTime);
        long seconds = difference.getSeconds();
        if (difference.isNegative() && difference.getNano() != 0) seconds++;
        return Math.toIntExact(seconds);
    }
}
