package pl.krktransit.realtime.ingestion.application.collection;

import java.util.List;
import java.util.Map;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;

public record DecodedFeed(List<RealtimeObservation> observations, Map<EntityRejection, Integer> rejections) {
    public DecodedFeed {
        observations = List.copyOf(observations);
        rejections = Map.copyOf(rejections);
    }

    public int rejectedEntities() {
        return rejections.values().stream().mapToInt(Integer::intValue).sum();
    }
}
