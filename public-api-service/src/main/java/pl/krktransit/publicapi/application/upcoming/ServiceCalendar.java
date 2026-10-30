package pl.krktransit.publicapi.application.upcoming;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ServiceCalendar {
    public static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final long SECONDS_PER_DAY = 86_400L;

    public LocalDate serviceDate(Instant observedAt, int plannedSeconds) {
        var local = observedAt.atZone(ZONE).toLocalDate();
        return Stream.of(local.minusDays(1), local, local.plusDays(1))
                .min(Comparator.comparing(date -> distance(observedAt, date, plannedSeconds))).orElseThrow();
    }

    public Instant plannedTime(LocalDate serviceDate, int plannedSeconds) {
        return serviceDate.atStartOfDay().plusDays(plannedSeconds / SECONDS_PER_DAY)
                .plusSeconds(plannedSeconds % SECONDS_PER_DAY).atZone(ZONE).toInstant();
    }

    private Duration distance(Instant observedAt, LocalDate date, int plannedSeconds) {
        return Duration.between(plannedTime(date, plannedSeconds), observedAt).abs();
    }
}
