package pl.krktransit.publicapi.adapter.inbound.http.error;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

final class FailureLogThrottle {
    private final Clock clock;
    private final Duration interval;
    private final ConcurrentHashMap<String, Instant> lastAllowed = new ConcurrentHashMap<>();

    FailureLogThrottle(Clock clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    boolean allows(String failure) {
        var now = clock.instant();
        var last = lastAllowed.get(failure);
        if (last == null) return lastAllowed.putIfAbsent(failure, now) == null;
        return intervalPassed(last, now) && lastAllowed.replace(failure, last, now);
    }

    private boolean intervalPassed(Instant last, Instant now) {
        return !now.isBefore(last.plus(interval));
    }
}
