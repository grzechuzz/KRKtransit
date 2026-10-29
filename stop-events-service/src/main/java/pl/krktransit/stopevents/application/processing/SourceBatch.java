package pl.krktransit.stopevents.application.processing;

import java.util.List;
import pl.krktransit.stopevents.domain.source.SourceId;

public record SourceBatch(SourceId source, List<StreamEntry> entries) {
    public SourceBatch { entries = List.copyOf(entries); }
    public String endCursor() { return entries.getLast().id(); }
}
