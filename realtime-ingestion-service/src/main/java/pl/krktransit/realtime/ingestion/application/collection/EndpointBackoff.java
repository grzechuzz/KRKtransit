package pl.krktransit.realtime.ingestion.application.collection;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;

public final class EndpointBackoff {
    private final Duration pollDelay;
    private final List<Duration> failureDelays;
    private final double jitter;
    private final RandomGenerator random;
    private int consecutiveFailures;

    public EndpointBackoff(Duration pollDelay, List<Duration> failureDelays, double jitter, RandomGenerator random) {
        validate(pollDelay, failureDelays, jitter);
        this.pollDelay = pollDelay;
        this.failureDelays = List.copyOf(failureDelays);
        this.jitter = jitter;
        this.random = random;
    }

    private static void validate(Duration pollDelay, List<Duration> failureDelays, double jitter) {
        validateDelays(pollDelay, failureDelays);
        validateJitter(jitter);
    }

    private static void validateDelays(Duration pollDelay, List<Duration> failureDelays) {
        if (!pollDelay.isPositive() || !hasValidFailureDelays(failureDelays)) {
            throw new IllegalArgumentException("Polling delays must be positive and include at least one failure delay");
        }
    }

    private static boolean hasValidFailureDelays(List<Duration> failureDelays) {
        return !failureDelays.isEmpty() && failureDelays.stream().allMatch(Duration::isPositive);
    }

    private static void validateJitter(double jitter) {
        if (jitter < 0 || jitter >= 1) throw new IllegalArgumentException("Failure jitter must be in [0, 1)");
    }

    public Duration next(PollResult result) {
        if (!result.providerFailure()) {
            consecutiveFailures = 0;
            return pollDelay;
        }
        consecutiveFailures++;
        return atLeast(jittered(failureDelay()), result.retryAfter());
    }

    public int consecutiveFailures() { return consecutiveFailures; }

    private Duration failureDelay() {
        return failureDelays.get(Math.min(consecutiveFailures, failureDelays.size()) - 1);
    }

    private Duration jittered(Duration delay) {
        double factor = 1 - jitter * random.nextDouble();
        return Duration.ofNanos(Math.round(delay.toNanos() * factor));
    }

    private static Duration atLeast(Duration delay, Duration minimum) {
        return delay.compareTo(minimum) >= 0 ? delay : minimum;
    }
}
