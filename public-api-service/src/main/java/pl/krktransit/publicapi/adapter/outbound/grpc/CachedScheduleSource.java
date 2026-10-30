package pl.krktransit.publicapi.adapter.outbound.grpc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStops;

public final class CachedScheduleSource implements ScheduleSource {
    private static final int MAXIMUM_FEEDS = 1000;
    private final ScheduleSource delegate;
    private final Cache<FeedId, CurrentHash> hashes = Caffeine.newBuilder().maximumSize(MAXIMUM_FEEDS).build();
    private final Duration hashRefresh;
    private final Clock clock;

    public CachedScheduleSource(ScheduleSource delegate, Duration hashRefresh, Clock clock) {
        if (!hashRefresh.isPositive()) throw new IllegalArgumentException("Cache limits must be positive");
        this.delegate = delegate;
        this.hashRefresh = hashRefresh;
        this.clock = clock;
    }

    @Override
    public Optional<String> currentHash(FeedId feed) {
        var cached = hashes.getIfPresent(feed);
        return isFresh(cached) ? cached.value() : refreshHash(feed, cached);
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

    private record CurrentHash(String hash, Instant checkedAt) {
        Optional<String> value() { return Optional.ofNullable(hash); }
    }
}
