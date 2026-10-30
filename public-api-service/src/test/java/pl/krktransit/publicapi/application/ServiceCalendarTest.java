package pl.krktransit.publicapi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.upcoming.ServiceCalendar;

class ServiceCalendarTest {
    private final ServiceCalendar calendar = new ServiceCalendar();

    @Test
    void ordinaryDayUsesTheLocalDate() {
        var observed = Instant.parse("2026-11-02T13:31:00Z");
        assertThat(calendar.serviceDate(observed, 14 * 3600 + 32 * 60)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 11, 2), 14 * 3600 + 32 * 60))
                .isEqualTo(Instant.parse("2026-11-02T13:32:00Z"));
    }

    @Test
    void timesAfterMidnightBelongToThePreviousServiceDate() {
        var observed = Instant.parse("2026-11-02T23:30:00Z");
        assertThat(calendar.serviceDate(observed, 88_500)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 11, 2), 88_500)).isEqualTo(Instant.parse("2026-11-02T23:35:00Z"));
    }

    @Test
    void aTripStartingJustAfterMidnightMayBeReportedTheEveningBefore() {
        var observed = Instant.parse("2026-11-02T22:59:00Z");
        assertThat(calendar.serviceDate(observed, 120)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 11, 3), 120)).isEqualTo(Instant.parse("2026-11-02T23:02:00Z"));
    }

    @Test
    void daylightSavingDaysUseLocalWallClockSecondsLikeTheDetector() {
        assertThat(calendar.plannedTime(LocalDate.of(2026, 10, 25), 2 * 3600 + 30 * 60))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 10, 25), 8 * 3600)).isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
        assertThat(calendar.plannedTime(LocalDate.of(2027, 3, 28), 2 * 3600 + 30 * 60))
                .isEqualTo(Instant.parse("2027-03-28T01:30:00Z"));
        assertThat(calendar.plannedTime(LocalDate.of(2027, 3, 28), 8 * 3600)).isEqualTo(Instant.parse("2027-03-28T06:00:00Z"));
    }
}
