package pl.krktransit.realtime.ingestion.application.collection;

import java.time.Clock;
import pl.krktransit.realtime.ingestion.application.port.FeedDecoder;
import pl.krktransit.realtime.ingestion.application.port.FeedFetcher;
import pl.krktransit.realtime.ingestion.application.port.ObservationSink;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public final class CollectEndpoint {
    private final FeedFetcher fetcher;
    private final FeedDecoder decoder;
    private final ObservationSink sink;
    private final Clock clock;

    public CollectEndpoint(FeedFetcher fetcher, FeedDecoder decoder, ObservationSink sink, Clock clock) {
        this.fetcher = fetcher;
        this.decoder = decoder;
        this.sink = sink;
        this.clock = clock;
    }

    public PollResult collect(Endpoint endpoint) {
        FetchedFeed feed;
        try {
            feed = fetch(endpoint);
        } catch (FetchFailure failure) {
            if (failure.kind() == FetchFailure.Kind.CANCELLED) throw failure;
            return PollResult.fetchFailed(failure);
        }
        try {
            return handOff(decoder.decode(feed));
        } catch (DecodeFailure failure) {
            return PollResult.decodeFailed(failure);
        }
    }

    private FetchedFeed fetch(Endpoint endpoint) {
        byte[] body = fetcher.fetch(endpoint);
        return new FetchedFeed(endpoint, clock.instant(), body);
    }

    private PollResult handOff(DecodedFeed feed) {
        if (sink.offer(feed.observations())) return PollResult.admitted(feed);
        return PollResult.overflow(feed);
    }
}
