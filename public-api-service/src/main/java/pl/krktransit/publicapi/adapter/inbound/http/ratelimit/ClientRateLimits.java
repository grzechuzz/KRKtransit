package pl.krktransit.publicapi.adapter.inbound.http.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;

public final class ClientRateLimits {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final Map<String, Integer> perMinuteByRoute;
    private final Cache<ClientRoute, Bucket> buckets;

    public ClientRateLimits(Map<String, Integer> perMinuteByRoute, int maximumTrackedClients) {
        this.perMinuteByRoute = Map.copyOf(perMinuteByRoute);
        this.buckets = Caffeine.newBuilder().maximumSize(maximumTrackedClients).expireAfterAccess(WINDOW).build();
    }

    boolean tryAcquire(String route, String client) {
        var perMinute = perMinuteByRoute.get(route);
        return perMinute == null || buckets.get(new ClientRoute(route, client), ignored -> bucket(perMinute)).tryConsume(1);
    }

    private static Bucket bucket(int perMinute) {
        return Bucket.builder().addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, WINDOW)).build();
    }

    private record ClientRoute(String route, String client) {}
}
