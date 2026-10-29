package pl.krktransit.stopevents.application.processing;

import java.util.Map;
import java.util.Set;
import pl.krktransit.stopevents.domain.source.SourceId;

public record BatchOutcome(Map<SourceId, String> cursors, Set<SourceId> pausedSources, int eventCount) {
    public BatchOutcome {
        cursors = Map.copyOf(cursors);
        pausedSources = Set.copyOf(pausedSources);
    }
}
