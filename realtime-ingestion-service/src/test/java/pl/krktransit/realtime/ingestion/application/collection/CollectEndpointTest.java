package pl.krktransit.realtime.ingestion.application.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.ingestion.application.port.FeedDecoder;
import pl.krktransit.realtime.ingestion.application.port.FeedFetcher;
import pl.krktransit.realtime.ingestion.application.port.ObservationSink;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.support.Endpoints;

class CollectEndpointTest {
    private static final Clock CLOCK = Clock.fixed(Endpoints.FETCHED_AT, ZoneOffset.UTC);
    private static final Endpoint ENDPOINT = Endpoints.vehiclePositions(Endpoints.MPK_BUS);
    private static final FeedDecoder EMPTY = feed -> new DecodedFeed(List.of(), Map.of());
    private final RecordingSink sink = new RecordingSink();

    @Test
    void handsOffDecodedObservationsWithTheFetchCompletionTime() {
        var decoded = new ArrayList<FetchedFeed>();
        var collect = new CollectEndpoint(endpoint -> Optional.of(new byte[]{1, 2}), feed -> {
            decoded.add(feed);
            return new DecodedFeed(List.of(vehicle(), vehicle()), Map.of(EntityRejection.MISSING_TIMESTAMP, 3));
        }, sink, CLOCK);

        PollResult result = collect.collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.ADMITTED);
        assertThat(result.observations()).isEqualTo(2);
        assertThat(result.rejections()).containsExactly(Map.entry(EntityRejection.MISSING_TIMESTAMP, 3));
        assertThat(decoded).singleElement().satisfies(feed -> {
            assertThat(feed.endpoint()).isEqualTo(ENDPOINT);
            assertThat(feed.fetchedAt()).isEqualTo(Endpoints.FETCHED_AT);
            assertThat(feed.body()).containsExactly(1, 2);
        });
        assertThat(sink.offers).singleElement().satisfies(offer -> assertThat(offer).hasSize(2));
    }

    @Test
    void validEmptyFeedIsASuccessfulPoll() {
        var collect = new CollectEndpoint(endpoint -> Optional.of(new byte[0]), EMPTY, sink, CLOCK);

        PollResult result = collect.collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.ADMITTED);
        assertThat(result.providerFailure()).isFalse();
    }

    @Test
    void fetchFailureKeepsStatusAndRetryAfterAndSkipsDecoding() {
        FeedFetcher fetcher = endpoint -> {
            throw new FetchFailure(FetchFailure.Kind.HTTP_STATUS, 404, Duration.ofSeconds(9), "HTTP 404", null);
        };
        FeedDecoder decoder = feed -> { throw new AssertionError("must not decode"); };

        PollResult result = new CollectEndpoint(fetcher, decoder, sink, CLOCK).collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.FETCH_FAILED);
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.fetchFailure()).isEqualTo(FetchFailure.Kind.HTTP_STATUS);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(9));
        assertThat(result.providerFailure()).isTrue();
        assertThat(sink.offers).isEmpty();
    }

    @Test
    void unchangedFeedIsASuccessfulPollWithoutDecoding() {
        FeedDecoder decoder = feed -> { throw new AssertionError("must not decode"); };

        PollResult result = new CollectEndpoint(endpoint -> Optional.empty(), decoder, sink, CLOCK).collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.UNCHANGED);
        assertThat(result.providerFailure()).isFalse();
        assertThat(sink.offers).isEmpty();
    }

    @Test
    void malformedFeedForgetsItsValidators() {
        var forgotten = new AtomicBoolean();
        FeedFetcher fetcher = new FeedFetcher() {
            @Override
            public Optional<byte[]> fetch(Endpoint endpoint) { return Optional.of(new byte[]{9}); }

            @Override
            public void forget(Endpoint endpoint) { forgotten.set(endpoint.equals(ENDPOINT)); }
        };
        FeedDecoder decoder = feed -> { throw new DecodeFailure("GTFS-Realtime feed header is missing"); };

        new CollectEndpoint(fetcher, decoder, sink, CLOCK).collect(ENDPOINT);

        assertThat(forgotten).isTrue();
    }

    @Test
    void malformedFeedPublishesNothing() {
        FeedDecoder decoder = feed -> { throw new DecodeFailure("Malformed GTFS-Realtime protobuf"); };

        PollResult result = new CollectEndpoint(endpoint -> Optional.of(new byte[]{9}), decoder, sink, CLOCK).collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.DECODE_FAILED);
        assertThat(result.providerFailure()).isTrue();
        assertThat(sink.offers).isEmpty();
    }

    @Test
    void rejectedHandOffIsReportedAsLocalOverflow() {
        sink.accept = false;
        var collect = new CollectEndpoint(endpoint -> Optional.of(new byte[0]), feed -> new DecodedFeed(List.of(vehicle()), Map.of()), sink, CLOCK);

        PollResult result = collect.collect(ENDPOINT);

        assertThat(result.outcome()).isEqualTo(PollResult.Outcome.OVERFLOW);
        assertThat(result.observations()).isEqualTo(1);
        assertThat(result.providerFailure()).isFalse();
    }

    @Test
    void cancellationPropagates() {
        FeedFetcher fetcher = endpoint -> { throw new FetchFailure(FetchFailure.Kind.CANCELLED, "cancelled", null); };

        assertThatThrownBy(() -> new CollectEndpoint(fetcher, EMPTY, sink, CLOCK).collect(ENDPOINT))
                .isInstanceOf(FetchFailure.class);
    }

    private static VehicleObservation vehicle() {
        return new VehicleObservation(Endpoints.origin(Endpoints.MPK_BUS), "trip", "vehicle", "KR 1", null,
                null, null, null, null);
    }

    private static final class RecordingSink implements ObservationSink {
        private final List<List<RealtimeObservation>> offers = new ArrayList<>();
        private boolean accept = true;

        @Override
        public void awaitCapacity() {}

        @Override
        public boolean offer(List<RealtimeObservation> observations) {
            if (accept) offers.add(observations);
            return accept;
        }
    }
}
