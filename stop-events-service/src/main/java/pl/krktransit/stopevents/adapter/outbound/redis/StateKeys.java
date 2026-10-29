package pl.krktransit.stopevents.adapter.outbound.redis;

import java.time.LocalDate;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.VehicleKey;

public final class StateKeys {
    private final String prefix;
    public StateKeys(String prefix) { this.prefix = prefix; }
    String vehicle(VehicleKey key) { return prefix + ":vehicle:" + key.source() + ":" + key.licensePlate(); }
    String pin(TripKey key) { return prefix + ":pin:" + key.source() + ":" + key.tripId(); }
    String forecasts(TripKey key) { return prefix + ":trip:" + key.source() + ":" + key.tripId(); }
    String saved(TripKey key, LocalDate date) { return prefix + ":saved:" + key.source() + ":" + key.tripId() + ":" + date; }
    String cursor(SourceId source) { return prefix + ":cursor:" + source; }
    String fault() { return prefix + ":fault"; }
}
