package pl.krktransit.publicapi.adapter.outbound.grpc;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;
import pl.krktransit.publicapi.application.schedule.TripSummary;
import pl.krktransit.publicapi.support.MutableClock;

class CachedScheduleSourceTest {
    private static final FeedId BUS = new FeedId("krakow", "mpk-bus");
    private static final TripSummary SUMMARY = new TripSummary("194", "Krowodrza", null);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final Delegate delegate = new Delegate();
    private final CachedScheduleSource cache = new CachedScheduleSource(delegate, 100, 100, Duration.ofSeconds(30), clock);

    @Test
    void remembersFoundAndMissingTripsPerScheduleVersionButRetriesUnresolvedOnes() {
        delegate.found.put("found", SUMMARY);
        delegate.missing.add("missing");

        var first = cache.tripSummaries("hash", Set.of("found", "missing", "unresolved"));
        var second = cache.tripSummaries("hash", Set.of("found", "missing", "unresolved"));
        cache.tripSummaries("next-hash", Set.of("found"));

        assertThat(first).isEqualTo(second);
        assertThat(first.found()).containsExactly(Map.entry("found", SUMMARY));
        assertThat(first.missing()).containsExactly("missing");
        assertThat(delegate.requests).containsExactly(Set.of("found", "missing", "unresolved"), Set.of("unresolved"),
                Set.of("found"));
    }

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

    @Test
    void remembersTripStopsAndTheirAbsencePerScheduleVersionButNotFailures() {
        var stops = new TripStops("found", "139", List.of(new TripStop("s", "Rondo", null, null, null, 1, 0, 0)));
        delegate.stops.put("found", stops);

        assertThat(cache.tripStops("hash", "found")).contains(stops);
        assertThat(cache.tripStops("hash", "found")).contains(stops);
        assertThat(cache.tripStops("hash", "missing")).isEmpty();
        assertThat(cache.tripStops("hash", "missing")).isEmpty();
        assertThat(cache.tripStops("next-hash", "found")).contains(stops);
        delegate.stopsFailure = new ApiFailure.UpstreamUnavailable("Schedule service", null);
        assertThatThrownBy(() -> cache.tripStops("hash", "failing")).isInstanceOf(ApiFailure.UpstreamUnavailable.class);
        delegate.stopsFailure = null;
        assertThat(cache.tripStops("hash", "failing")).isEmpty();

        assertThat(delegate.stopRequests).containsExactly("hash/found", "hash/missing", "next-hash/found", "hash/failing",
                "hash/failing");
    }

    private static final class Delegate implements ScheduleSource {
        private final Map<String, TripSummary> found = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private final List<Set<String>> requests = new ArrayList<>();
        private final Map<String, TripStops> stops = new HashMap<>();
        private final List<String> stopRequests = new ArrayList<>();
        private ApiFailure stopsFailure;
        private String hash;
        private ApiFailure hashFailure;

        @Override
        public Optional<String> currentHash(FeedId feed) {
            if (hashFailure != null) throw hashFailure;
            return Optional.ofNullable(hash);
        }

        @Override
        public TripSummaries tripSummaries(String hash, Set<String> tripIds) {
            requests.add(Set.copyOf(tripIds));
            var resolved = new HashMap<String, TripSummary>();
            tripIds.stream().filter(found::containsKey).forEach(tripId -> resolved.put(tripId, found.get(tripId)));
            return new TripSummaries(resolved, tripIds.stream().filter(missing::contains).collect(toSet()));
        }

        @Override
        public Optional<TripStops> tripStops(String hash, String tripId) {
            stopRequests.add(hash + "/" + tripId);
            if (stopsFailure != null) throw stopsFailure;
            return Optional.ofNullable(stops.get(tripId));
        }

        @Override
        public Optional<Shape> shape(String hash, String shapeId) { return Optional.empty(); }
    }
}
