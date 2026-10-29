package pl.krktransit.stopevents.application.port;

import java.util.List;
import java.util.Map;
import pl.krktransit.stopevents.application.processing.SourceBatch;
import pl.krktransit.stopevents.domain.source.SourceId;

public interface ObservationSource {
    String tail(SourceId source);
    List<SourceBatch> read(Map<SourceId, String> cursors);
}
