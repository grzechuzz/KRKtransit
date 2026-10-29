package pl.krktransit.livevehicles.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.VehiclePosition;

public interface ObservationSource {
    Map<SourceId, String> recentCursors(Set<SourceId> sources, Duration lookback);
    ReadBatch read(Map<SourceId, String> cursors);

    record ReadBatch(Map<SourceId, String> cursors, List<VehiclePosition> positions) {
        public ReadBatch { cursors = Map.copyOf(cursors); positions = List.copyOf(positions); }
    }
}
