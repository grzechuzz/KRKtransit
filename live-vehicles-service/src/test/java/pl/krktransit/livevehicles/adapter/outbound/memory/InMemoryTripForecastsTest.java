package pl.krktransit.livevehicles.adapter.outbound.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.domain.StopForecast;
import pl.krktransit.livevehicles.domain.TripForecast;

class InMemoryTripForecastsTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private final InMemoryTripForecasts forecasts = new InMemoryTripForecasts(Duration.ofMinutes(5), 10);

    @Test
    void newestForecastReplacesThePreviousOneOfTheTrip() {
        var first = forecast("trip", NOW.minusSeconds(30), 100);
        var second = forecast("trip", NOW, 200);
        forecasts.accept(first, NOW);
        forecasts.accept(second, NOW);
        assertThat(forecasts.find("trip", NOW)).contains(second);
    }

    @Test
    void forecastFetchedEarlierCannotReplaceANewerOneDuringReplay() {
        var current = forecast("trip", NOW, 200);
        forecasts.accept(current, NOW);
        forecasts.accept(forecast("trip", NOW.minusSeconds(1), 100), NOW);
        assertThat(forecasts.find("trip", NOW)).contains(current);
    }

    @Test
    void laterEntryWithTheSameFetchTimeWins() {
        var later = forecast("trip", NOW, 200);
        forecasts.accept(forecast("trip", NOW, 100), NOW);
        forecasts.accept(later, NOW);
        assertThat(forecasts.find("trip", NOW)).contains(later);
    }

    @Test
    void forecastWithoutStopsReplacesAnOlderOneSoNoStaleTimesRemain() {
        var empty = new TripForecast("trip", NOW, List.of());
        forecasts.accept(forecast("trip", NOW.minusSeconds(30), 100), NOW);
        forecasts.accept(empty, NOW);
        assertThat(forecasts.find("trip", NOW)).contains(empty);
    }

    @Test
    void forecastsAreVisibleOnlyUntilTheRetentionEnds() {
        var forecast = forecast("trip", NOW, 100);
        forecasts.accept(forecast, NOW);
        assertThat(forecasts.find("trip", NOW.plusSeconds(299))).contains(forecast);
        assertThat(forecasts.find("trip", NOW.plusSeconds(300))).isEmpty();
        forecasts.accept(forecast("old", NOW.minusSeconds(300), 100), NOW);
        assertThat(forecasts.find("old", NOW)).isEmpty();
        assertThat(forecasts.find("unknown", NOW)).isEmpty();
    }

    @Test
    void capacityIsBoundedAndExpiredEntriesAreRemovedBeforeEviction() {
        var small = new InMemoryTripForecasts(Duration.ofMinutes(5), 2);
        small.accept(forecast("expiring", NOW.minusSeconds(298), 100), NOW);
        small.accept(forecast("kept", NOW, 100), NOW);
        small.accept(forecast("newer", NOW.plusSeconds(2), 100), NOW.plusSeconds(2));
        assertThat(small.find("kept", NOW.plusSeconds(2))).isPresent();
        assertThat(small.find("newer", NOW.plusSeconds(2))).isPresent();
        small.accept(forecast("newest", NOW.plusSeconds(3), 100), NOW.plusSeconds(3));
        assertThat(small.find("kept", NOW.plusSeconds(3))).isEmpty();
        assertThat(small.find("newer", NOW.plusSeconds(3))).isPresent();
        assertThat(small.find("newest", NOW.plusSeconds(3))).isPresent();
    }

    @Test
    void knownTripAtCapacityEvictsNothing() {
        var small = new InMemoryTripForecasts(Duration.ofMinutes(5), 2);
        small.accept(forecast("first", NOW, 100), NOW);
        small.accept(forecast("second", NOW, 100), NOW);
        small.accept(forecast("second", NOW.plusSeconds(1), 200), NOW.plusSeconds(1));
        assertThat(small.find("first", NOW.plusSeconds(1))).isPresent();
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new InMemoryTripForecasts(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryTripForecasts(Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private TripForecast forecast(String tripId, Instant fetchedAt, long arrivalSeconds) {
        return new TripForecast(tripId, fetchedAt, List.of(new StopForecast(1, "stop", NOW.plusSeconds(arrivalSeconds))));
    }
}
