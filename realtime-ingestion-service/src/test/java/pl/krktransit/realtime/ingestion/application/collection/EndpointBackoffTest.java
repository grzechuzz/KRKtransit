package pl.krktransit.realtime.ingestion.application.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.ingestion.support.Endpoints;
import pl.krktransit.realtime.ingestion.support.Observations;

class EndpointBackoffTest {
    private static final List<Duration> FAILURE_DELAYS = List.of(Duration.ofSeconds(4), Duration.ofSeconds(8),
            Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120));

    @Test
    void successUsesTheNormalPostWorkDelay() {
        var backoff = backoff(0.1, 0.5);

        assertThat(backoff.next(PollResult.admitted(feed(10)))).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.consecutiveFailures()).isZero();
    }

    @Test
    void consecutiveFailuresWalkTheAgreedDelaysAndStayAtTheCeiling() {
        var backoff = backoff(0, 0.5);
        var failure = fetchFailure(Duration.ZERO);

        var delays = IntStream.range(0, 8).mapToObj(ignored -> backoff.next(failure)).toList();

        assertThat(delays).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16),
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(120),
                Duration.ofSeconds(120));
        assertThat(backoff.consecutiveFailures()).isEqualTo(8);
    }

    @Test
    void successResetsTheFailureCounter() {
        var backoff = backoff(0, 0.5);
        backoff.next(fetchFailure(Duration.ZERO));
        backoff.next(fetchFailure(Duration.ZERO));

        assertThat(backoff.next(PollResult.admitted(feed(0)))).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.next(fetchFailure(Duration.ZERO))).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void decodeFailuresUseTheSameBackoff() {
        var backoff = backoff(0, 0.5);
        backoff.next(PollResult.decodeFailed(new DecodeFailure("bad")));

        assertThat(backoff.next(PollResult.decodeFailed(new DecodeFailure("bad")))).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void localOverflowIsNotAProviderFailure() {
        var backoff = backoff(0, 0.5);
        backoff.next(fetchFailure(Duration.ZERO));

        assertThat(backoff.next(PollResult.overflow(feed(500)))).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.consecutiveFailures()).isZero();
    }

    @Test
    void jitterOnlyShortensFailureDelaysWithinItsRange() {
        assertThat(backoff(0.1, 0.0).next(fetchFailure(Duration.ZERO))).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff(0.1, 0.5).next(fetchFailure(Duration.ZERO))).isEqualTo(Duration.ofMillis(3800));
        assertThat(backoff(0.1, 0.999).next(PollResult.admitted(feed(1)))).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void neverRetriesEarlierThanALongerRetryAfter() {
        var backoff = backoff(0.1, 0.5);

        assertThat(backoff.next(fetchFailure(Duration.ofSeconds(600)))).isEqualTo(Duration.ofSeconds(600));
        assertThat(backoff.next(fetchFailure(Duration.ofSeconds(1)))).isEqualTo(Duration.ofMillis(7600));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new EndpointBackoff(Duration.ZERO, FAILURE_DELAYS, 0.1, fixed(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointBackoff(Duration.ofSeconds(4), List.of(), 0.1, fixed(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointBackoff(Duration.ofSeconds(4), FAILURE_DELAYS, 1.0, fixed(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EndpointBackoff backoff(double jitter, double random) {
        return new EndpointBackoff(Duration.ofSeconds(4), FAILURE_DELAYS, jitter, fixed(random));
    }

    private static PollResult fetchFailure(Duration retryAfter) {
        return PollResult.fetchFailed(new FetchFailure(FetchFailure.Kind.HTTP_STATUS, 429, retryAfter, "HTTP 429", null));
    }

    private static DecodedFeed feed(int observations) {
        var vehicle = Observations.vehicle(Endpoints.MPK_BUS, "trip");
        return new DecodedFeed(Collections.nCopies(observations, vehicle), Map.of());
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() { throw new UnsupportedOperationException(); }

            @Override
            public double nextDouble() { return value; }
        };
    }
}
