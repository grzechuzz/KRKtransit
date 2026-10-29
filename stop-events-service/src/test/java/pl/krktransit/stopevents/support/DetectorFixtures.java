package pl.krktransit.stopevents.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import pl.krktransit.stopevents.domain.event.*;
import pl.krktransit.stopevents.domain.observation.*;
import pl.krktransit.stopevents.domain.schedule.*;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.*;

public final class DetectorFixtures {
    public static final SourceId SOURCE = new SourceId("krakow", "mpk-bus");
    public static final String TRIP = SOURCE.scope("trip");
    public static final String HASH = "a".repeat(64);
    public static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    public static final Instant BASE = Instant.parse("2026-10-28T09:00:00Z");

    private DetectorFixtures() {}
    public static TripContext trip() { return trip(HASH, TRIP, 0, "Destination"); }
    public static TripContext trip(String hash, String id, Integer direction, String headsign) {
        return new TripContext(hash, id, "111", direction, headsign, 4, List.of(
                stop(1, "A", 36000), stop(2, "B", 36060), stop(3, "C", 36120), stop(4, "A", 36180)));
    }
    public static ScheduledStop stop(int seq, String id, int seconds) {
        return new ScheduledStop(seq, SOURCE.scope(id), id, null, seconds);
    }
    public static VehicleObservation vp(int seq, Integer status, int seconds) {
        return new VehicleObservation(SOURCE, BASE.plusSeconds(seconds), TRIP, "KR123", "vehicle", seq,
                status, BASE.plusSeconds(seconds));
    }
    public static VehicleState previous(int seq) {
        return new VehicleState(TRIP, "KR123", seq, BASE, BASE);
    }
    public static TripForecasts forecasts() {
        return new TripForecasts(Map.of(
                1, new RetainedForecast(SOURCE.scope("A"), BASE, BASE),
                2, new RetainedForecast(SOURCE.scope("B"), BASE.plusSeconds(60), BASE.plusSeconds(70)),
                3, new RetainedForecast(SOURCE.scope("C"), BASE.plusSeconds(120), BASE.plusSeconds(130)),
                4, new RetainedForecast(SOURCE.scope("A"), BASE.plusSeconds(180), BASE.plusSeconds(200))), 1, BASE);
    }
    public static SavedStops emptySaved() { return new SavedStops(Map.of()); }
    public static ForecastUpdate tu(int seconds, StopForecast... stops) {
        return new ForecastUpdate(SOURCE, BASE.plusSeconds(seconds), TRIP, List.of(stops));
    }
    public static StopForecast forecast(Integer seq, String stop, int seconds) {
        return new StopForecast(seq, SOURCE.scope(stop), BASE.plusSeconds(seconds), null);
    }
    public static StopEvent event(int sequence, int delay, Instant time) {
        var stop = trip().stop(sequence);
        return new StopEvent(SOURCE, TRIP, DATE, sequence, stop.stopId(), "111", stop.name(), null, 0,
                "Destination", time.minusSeconds(delay), time, delay, "vehicle", "KR123",
                DetectionMethod.SEQ_JUMP, true, HASH, 4);
    }
}
