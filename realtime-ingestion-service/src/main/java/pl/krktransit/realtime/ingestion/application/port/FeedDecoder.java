package pl.krktransit.realtime.ingestion.application.port;

import pl.krktransit.realtime.ingestion.application.collection.DecodedFeed;
import pl.krktransit.realtime.ingestion.application.collection.FetchedFeed;

public interface FeedDecoder {
    DecodedFeed decode(FetchedFeed feed);
}
