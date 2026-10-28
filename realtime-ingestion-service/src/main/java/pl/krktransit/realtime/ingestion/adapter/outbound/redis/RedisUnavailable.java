package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

public final class RedisUnavailable extends RuntimeException {
    RedisUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
