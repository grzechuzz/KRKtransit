package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.support.MutableClock;

class CachedScheduleSourceTest {
    private static final FeedId BUS = new FeedId("krakow", "mpk-bus");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final Delegate delegate = new Delegate();
    private final CachedScheduleSource cache = new CachedScheduleSource(delegate, Duration.ofSeconds(30), clock);

    @Test
    void refreshesTheCurrentHashAfterTheIntervalAndServesTheLastKnownOneDuringAnOutage() {
        delegate.hash = "first";
        assertThat(cache.currentHash(BUS)).contains("first");
        delegate.hash = "second";
        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.currentHash(BUS)).contains("first");

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.currentHash(BUS)).contains("second");

        delegate.hashFailure = new ApiFailure.UpstreamUnavailable("Schedule service", null);
        clock.advance(Duration.ofSeconds(30));
        assertThat(cache.currentHash(BUS)).contains("second");
        assertThatThrownBy(() -> cache.currentHash(new FeedId("krakow", "mpk-tram")))
                .isInstanceOf(ApiFailure.UpstreamUnavailable.class);
    }

    private static final class Delegate implements ScheduleSource {
        private String hash;
        private ApiFailure hashFailure;

        @Override
        public Optional<String> currentHash(FeedId feed) {
            if (hashFailure != null) throw hashFailure;
            return Optional.ofNullable(hash);
        }

        @Override
        public Optional<TripStops> tripStops(String hash, String tripId) { return Optional.empty(); }

        @Override
        public Optional<Shape> shape(String hash, String shapeId) { return Optional.empty(); }
    }
}
