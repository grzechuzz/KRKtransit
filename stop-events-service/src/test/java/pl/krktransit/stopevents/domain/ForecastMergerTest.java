package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.domain.detection.*;
import pl.krktransit.stopevents.domain.observation.StopForecast;

class ForecastMergerTest {
    private final ForecastMerger merger = new ForecastMerger();

    @Test
    void repeatedTerminalStopsResolveInProviderOrderBeforeStart() {
        var result = merger.merge(tu(0, forecast(null, "A", 0), forecast(null, "B", 60),
                forecast(null, "C", 120), forecast(null, "A", 180)), trip(), null);
        assertThat(result.stops()).containsOnlyKeys(1, 2, 3, 4);
        assertThat(result.stops().get(4).firstSeenArrival()).isEqualTo(BASE.plusSeconds(180));
    }

    @Test
    void repeatedStopUsesLastMinimumForMidTripAndTerminalOnlyUpdates() {
        var mid = merger.merge(tu(0, forecast(null, "B", 60), forecast(null, "C", 120), forecast(null, "A", 180)), trip(), null);
        var terminal = merger.merge(tu(10, forecast(null, "A", 190)), trip(), mid);
        assertThat(terminal.stops()).containsOnlyKeys(2, 3, 4);
        assertThat(terminal.lastMinSequence()).isEqualTo(4);
        assertThat(terminal.stops().get(4).firstSeenArrival()).isEqualTo(BASE.plusSeconds(190));
    }

    @Test
    void retainedFirstForecastChangesOnlyWhenMinimumAdvances() {
        var retained = merger.merge(tu(0, forecast(2, "B", 60), forecast(3, "C", 120)), trip(), null);
        var same = merger.merge(tu(5, forecast(2, "B", 70)), trip(), retained);
        assertThat(same.stops().get(2).firstSeenArrival()).isEqualTo(BASE.plusSeconds(60));
        assertThat(same.stops().get(2).lastSeenArrival()).isEqualTo(BASE.plusSeconds(70));
        assertThat(same.stops()).containsKey(3);
        var moved = merger.merge(tu(10, forecast(3, "C", 150)), trip(), same);
        assertThat(moved.stops().get(3).firstSeenArrival()).isEqualTo(BASE.plusSeconds(150));
    }

    @Test
    void suppliedZeroIsPreservedAndBoundsFollowingFallbacks() {
        var result = new StopSequenceResolver().resolve(List.of(forecast(0, "A", 0), forecast(null, "A", 180)), trip(), null);
        assertThat(result).extracting(StopSequenceResolver.ResolvedForecast::sequence).containsExactly(0, 1);
    }

    @Test
    void suppliedSequenceBeatsTheStopIdMapping() {
        var result = merger.merge(tu(0, forecast(4, "A", 180)), trip(), null);
        assertThat(result.stops()).containsOnlyKeys(4);
    }

    @Test
    void departureFallbackWorksAndUnknownOrUnusableStopsAreIgnored() {
        var departure = new StopForecast(2, SOURCE.scope("B"), null, BASE.plusSeconds(60));
        var result = merger.merge(tu(0, departure, forecast(null, "unknown", 0),
                new StopForecast(3, SOURCE.scope("C"), null, null)), trip(), null);
        assertThat(result.stops()).containsOnlyKeys(2);
    }

    @Test
    void repeatedMiddleStopCanResolveBothOccurrences() {
        var context = new pl.krktransit.stopevents.domain.schedule.TripContext(HASH, TRIP, "247", 0, "End", 5,
                List.of(stop(1, "A", 0), stop(2, "B", 60), stop(3, "C", 120), stop(4, "B", 180), stop(5, "D", 240)));
        var result = merger.merge(tu(0, forecast(null, "B", 60), forecast(null, "C", 120),
                forecast(null, "B", 180)), context, null);
        assertThat(result.stops()).containsOnlyKeys(2, 3, 4);
    }
}
