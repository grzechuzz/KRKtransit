package pl.krktransit.realtime.ingestion.application.port;

import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public interface FeedFetcher {
    byte[] fetch(Endpoint endpoint);
}
