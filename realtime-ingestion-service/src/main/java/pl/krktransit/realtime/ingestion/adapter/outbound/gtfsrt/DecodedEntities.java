package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import pl.krktransit.realtime.ingestion.application.collection.DecodedFeed;
import pl.krktransit.realtime.ingestion.application.collection.EntityRejection;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;

final class DecodedEntities {
    private final List<RealtimeObservation> accepted = new ArrayList<>();
    private final Map<EntityRejection, Integer> rejections = new EnumMap<>(EntityRejection.class);

    void add(RealtimeObservation observation) {
        var rejection = switch (observation) {
            case VehicleObservation vehicle -> EntityFilter.vehicle(vehicle);
            case TripUpdateObservation update -> EntityFilter.tripUpdate(update);
        };
        rejection.ifPresentOrElse(reason -> rejections.merge(reason, 1, Integer::sum), () -> accepted.add(observation));
    }

    DecodedFeed result() {
        return new DecodedFeed(accepted, rejections);
    }
}
