package pl.krktransit.realtime.ingestion.support;

import java.util.List;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleLocation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

public final class Observations {
    private Observations() {}

    public static VehicleObservation vehicle(SourceId source, String tripId) {
        return new VehicleObservation(Endpoints.origin(source), tripId, "0042", "KR 1",
                new VehicleLocation(50.06f, 19.94f, 90f), "stop", 3, 1, 1_761_387_290L);
    }

    public static TripUpdateObservation tripUpdate(SourceId source, String tripId) {
        return new TripUpdateObservation(Endpoints.origin(source), tripId, 0, "0042", 1_761_387_280L,
                List.of(new StopUpdate(1, "a", new StopEvent(1_761_387_400L, null), null, null),
                        new StopUpdate(2, "b", null, new StopEvent(null, 20), 0)));
    }
}
