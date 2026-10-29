package pl.krktransit.realtime.ingestion.application.port;

import java.util.Optional;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public interface FeedFetcher {
    Optional<byte[]> fetch(Endpoint endpoint);

    default void forget(Endpoint endpoint) {}
}
