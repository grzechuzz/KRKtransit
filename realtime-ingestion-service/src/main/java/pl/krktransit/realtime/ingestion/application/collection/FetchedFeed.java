package pl.krktransit.realtime.ingestion.application.collection;

import java.time.Instant;
import java.util.Objects;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public record FetchedFeed(Endpoint endpoint, Instant fetchedAt, byte[] body) {
    public FetchedFeed {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(body, "body");
    }
}
