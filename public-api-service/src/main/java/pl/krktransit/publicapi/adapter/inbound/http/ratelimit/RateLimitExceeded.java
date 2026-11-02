package pl.krktransit.publicapi.adapter.inbound.http.ratelimit;

public final class RateLimitExceeded extends RuntimeException {
    RateLimitExceeded(String route) { super("Rate limit exceeded for " + route); }
}
