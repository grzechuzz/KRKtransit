package pl.krktransit.stopevents.adapter.outbound.schedule;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import pl.krktransit.stopevents.application.port.ScheduleContexts;
import pl.krktransit.stopevents.domain.source.SourceId;

public final class CachedScheduleContexts implements ScheduleContexts {
    private final ScheduleContexts delegate;
    private final Cache<TripKey, Lookup> contexts;
    private final Cache<SourceId, CurrentHash> hashes = Caffeine.newBuilder().maximumSize(1000).build();
    private final Duration refresh;
    private final Clock clock;

    public CachedScheduleContexts(ScheduleContexts delegate, int maximumSize, Duration refresh, Clock clock) {
        this.delegate = delegate;
        this.contexts = Caffeine.newBuilder().maximumSize(maximumSize).build();
        this.refresh = refresh;
        this.clock = clock;
    }

    @Override
    public String currentHash(SourceId source) {
        var cached = hashes.getIfPresent(source);
        if (cached != null && clock.instant().isBefore(cached.checkedAt().plus(refresh))) return cached.hash();
        String hash = delegate.currentHash(source);
        hashes.put(source, new CurrentHash(hash, clock.instant()));
        return hash;
    }

    @Override
    public Lookup tripContext(String hash, String tripId) {
        var key = new TripKey(hash, tripId);
        var cached = contexts.getIfPresent(key);
        if (cached != null) return cached;
        var loaded = delegate.tripContext(hash, tripId);
        if (!(loaded instanceof VersionUnavailable)) contexts.put(key, loaded);
        return loaded;
    }

    private record TripKey(String hash, String tripId) {}
    private record CurrentHash(String hash, Instant checkedAt) {}
}
