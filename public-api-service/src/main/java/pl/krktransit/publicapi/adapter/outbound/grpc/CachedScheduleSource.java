package pl.krktransit.publicapi.adapter.outbound.grpc;

import static java.util.stream.Collectors.toSet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;
import pl.krktransit.publicapi.application.schedule.TripSummary;

public final class CachedScheduleSource implements ScheduleSource {
    private static final int MAXIMUM_FEEDS = 1000;
    private final ScheduleSource delegate;
    private final Cache<TripKey, CachedTrip> trips;
    private final Cache<FeedId, CurrentHash> hashes = Caffeine.newBuilder().maximumSize(MAXIMUM_FEEDS).build();
    private final Duration hashRefresh;
    private final Clock clock;

    public CachedScheduleSource(ScheduleSource delegate, int tripCacheSize, Duration hashRefresh, Clock clock) {
        if (tripCacheSize <= 0 || !hashRefresh.isPositive()) throw new IllegalArgumentException("Cache limits must be positive");
        this.delegate = delegate;
        this.trips = Caffeine.newBuilder().maximumSize(tripCacheSize).build();
        this.hashRefresh = hashRefresh;
        this.clock = clock;
    }

    @Override
    public Optional<String> currentHash(FeedId feed) {
        var cached = hashes.getIfPresent(feed);
        return isFresh(cached) ? cached.value() : refreshHash(feed, cached);
    }

    @Override
    public TripSummaries tripSummaries(String hash, Set<String> tripIds) {
        var cached = cachedTrips(hash, tripIds);
        var unresolved = tripIds.stream().filter(tripId -> !cached.resolves(tripId)).collect(toSet());
        if (unresolved.isEmpty()) return cached;
        var loaded = delegate.tripSummaries(hash, unresolved);
        remember(hash, loaded);
        return cached.with(loaded);
    }

    @Override
    public Optional<TripStops> tripStops(String hash, String tripId) { return delegate.tripStops(hash, tripId); }

    @Override
    public Optional<Shape> shape(String hash, String shapeId) { return delegate.shape(hash, shapeId); }

    private boolean isFresh(CurrentHash cached) {
        return cached != null && clock.instant().isBefore(cached.checkedAt().plus(hashRefresh));
    }

    private Optional<String> refreshHash(FeedId feed, CurrentHash stale) {
        try {
            var hash = delegate.currentHash(feed);
            hashes.put(feed, new CurrentHash(hash.orElse(null), clock.instant()));
            return hash;
        } catch (ApiFailure.UpstreamUnavailable | ApiFailure.UpstreamBusy failure) {
            if (stale == null) throw failure;
            return stale.value();
        }
    }

    private TripSummaries cachedTrips(String hash, Set<String> tripIds) {
        var found = new HashMap<String, TripSummary>();
        var missing = new HashSet<String>();
        for (var tripId : tripIds) {
            switch (trips.getIfPresent(new TripKey(hash, tripId))) {
                case Known known -> found.put(tripId, known.summary());
                case Absent ignored -> missing.add(tripId);
                case null -> { }
            }
        }
        return new TripSummaries(found, missing);
    }

    private void remember(String hash, TripSummaries loaded) {
        loaded.found().forEach((tripId, summary) -> trips.put(new TripKey(hash, tripId), new Known(summary)));
        loaded.missing().forEach(tripId -> trips.put(new TripKey(hash, tripId), new Absent()));
    }

    private record TripKey(String hash, String tripId) {}

    private record CurrentHash(String hash, Instant checkedAt) {
        Optional<String> value() { return Optional.ofNullable(hash); }
    }

    private sealed interface CachedTrip permits Known, Absent {}

    private record Known(TripSummary summary) implements CachedTrip {}

    private record Absent() implements CachedTrip {}
}
