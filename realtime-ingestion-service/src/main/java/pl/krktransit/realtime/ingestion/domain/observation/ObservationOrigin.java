package pl.krktransit.realtime.ingestion.domain.observation;

import java.time.Instant;
import java.util.Objects;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

public record ObservationOrigin(SourceId source, Instant fetchedAt, Long feedTimestamp) {
    public ObservationOrigin {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
    }
}
