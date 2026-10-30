package pl.krktransit.publicapi.adapter.inbound.http;

final class RateLimitExceeded extends RuntimeException {
    RateLimitExceeded(String route) { super("Rate limit exceeded for " + route); }
}
