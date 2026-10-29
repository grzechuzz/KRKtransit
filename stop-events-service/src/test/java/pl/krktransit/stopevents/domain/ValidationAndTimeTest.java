package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.domain.detection.EventValidator;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.state.*;
import pl.krktransit.stopevents.domain.time.ServiceCalendar;

class ValidationAndTimeTest {
    private final ServiceCalendar calendar = new ServiceCalendar();

    @Test
    void arrivalThresholdExemptsOnlySequenceOneAndIncludesMinus180() {
        var validator = new EventValidator();
        assertThat(validator.validate(List.of(event(1, -1000, BASE)), emptySaved())).hasSize(1);
        assertThat(validator.validate(List.of(event(2, -181, BASE)), emptySaved())).isEmpty();
        assertThat(validator.validate(List.of(event(2, -180, BASE)), emptySaved())).hasSize(1);
    }

    @Test
    void previousStopControlsDelayDropAndStrictlyIncreasingTime() {
        var validator = new EventValidator();
        var saved = new SavedStops(Map.of(new EventKey(TRIP, DATE, 1), new SavedStop(200, BASE)));
        assertThat(validator.validate(List.of(event(2, 19, BASE.plusSeconds(1))), saved)).isEmpty();
        assertThat(validator.validate(List.of(event(2, 20, BASE.plusSeconds(1))), saved)).hasSize(1);
        assertThat(validator.validate(List.of(event(2, 200, BASE)), saved)).isEmpty();
    }

    @Test
    void acceptedCandidateIsVisibleToTheNextCandidate() {
        var accepted = new EventValidator().validate(List.of(event(1, 500, BASE),
                event(2, 0, BASE.plusSeconds(60))), emptySaved());
        assertThat(accepted).hasSize(1);
    }

    @Test
    void overnightServiceDateKeepsTheExistingTwoBranches() {
        var afterMidnight = Instant.parse("2026-10-29T00:30:00Z");
        assertThat(calendar.serviceDate(afterMidnight, 25 * 3600)).isEqualTo(DATE);
        assertThat(calendar.serviceDate(afterMidnight, 23 * 3600)).isEqualTo(DATE);
        assertThat(calendar.serviceDate(afterMidnight, 3600)).isEqualTo(DATE.plusDays(1));
        assertThat(calendar.plannedTime(DATE, 25 * 3600)).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
    }

    @Test
    void serviceDateIsTheDayWhosePlannedTimeIsNearestToTheObservation() {
        assertThat(calendar.serviceDate(Instant.parse("2026-10-28T22:59:00Z"), 24 * 3600)).isEqualTo(DATE);
        assertThat(calendar.serviceDate(Instant.parse("2026-10-28T22:35:00Z"), 24 * 3600 + 25 * 60)).isEqualTo(DATE);
        assertThat(calendar.serviceDate(Instant.parse("2026-10-28T21:39:00Z"), 5 * 3600 + 41 * 60))
                .isEqualTo(DATE.plusDays(1));
    }

    @Test
    void firstStopMayBeAtMostOneHourEarlyAndNoStopMoreThanThreeHoursLate() {
        var validator = new EventValidator();
        assertThat(validator.validate(List.of(event(1, -3600, BASE)), emptySaved())).hasSize(1);
        assertThat(validator.validate(List.of(event(1, -3601, BASE)), emptySaved())).isEmpty();
        assertThat(validator.validate(List.of(event(2, 10800, BASE)), emptySaved())).hasSize(1);
        assertThat(validator.validate(List.of(event(2, 10801, BASE)), emptySaved())).isEmpty();
    }

    @Test
    void earliestServiceDateCoversTheOvernightBranchOfTheLocalDay() {
        assertThat(calendar.earliestServiceDate(Instant.parse("2026-10-28T23:30:00Z"))).isEqualTo(DATE);
        assertThat(calendar.earliestServiceDate(Instant.parse("2026-10-28T22:30:00Z"))).isEqualTo(DATE.minusDays(1));
    }

    @Test
    void plannedTimesUseLocalArithmeticAcrossBothClockChanges() {
        assertThat(calendar.plannedTime(LocalDate.of(2026, 3, 29), 4 * 3600))
                .isEqualTo(Instant.parse("2026-03-29T02:00:00Z"));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 10, 25), 4 * 3600))
                .isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        assertThat(calendar.plannedTime(LocalDate.of(2026, 10, 25), 9000))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    }

    @Test
    void negativeFractionalDelayTruncatesTowardsZero() {
        assertThat(calendar.delay(BASE.minusNanos(500_000_000), BASE)).isZero();
        assertThat(calendar.delay(BASE.minusNanos(1_500_000_000), BASE)).isEqualTo(-1);
    }

    @Test
    void pinLifecycleUsesObservationTimeWithStrictThreeHourBoundary() {
        var pin = new TripPin(HASH, BASE);
        assertThat(pin.expired(BASE.plusSeconds(10800), Duration.ofHours(3))).isFalse();
        assertThat(pin.expired(BASE.plusSeconds(10801), Duration.ofHours(3))).isTrue();
        assertThat(pin.observedAt(BASE.minusSeconds(1)).fetchedAt()).isEqualTo(BASE);
    }

    @Test
    void scopedIdsPreserveOriginalBytes() {
        assertThat(SOURCE.scope(" 001:a ")).isEqualTo("krakow:mpk-bus: 001:a ");
    }
}
