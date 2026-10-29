package pl.krktransit.realtime.ingestion.application.collection;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record PollResult(Outcome outcome, int observations, Map<EntityRejection, Integer> rejections,
                         FetchFailure.Kind fetchFailure, int status, Duration retryAfter, String detail) {
    public enum Outcome { ADMITTED, UNCHANGED, OVERFLOW, FETCH_FAILED, DECODE_FAILED }

    public PollResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(retryAfter, "retryAfter");
        rejections = Map.copyOf(rejections);
    }

    static PollResult admitted(DecodedFeed feed) {
        return new PollResult(Outcome.ADMITTED, feed.observations().size(), feed.rejections(), null, 0,
                Duration.ZERO, null);
    }

    static PollResult unchanged() {
        return new PollResult(Outcome.UNCHANGED, 0, Map.of(), null, 0, Duration.ZERO, "not modified");
    }

    static PollResult overflow(DecodedFeed feed) {
        return new PollResult(Outcome.OVERFLOW, feed.observations().size(), feed.rejections(), null, 0,
                Duration.ZERO, "publication buffer is full");
    }

    static PollResult fetchFailed(FetchFailure failure) {
        return new PollResult(Outcome.FETCH_FAILED, 0, Map.of(), failure.kind(), failure.status(),
                failure.retryAfter(), failure.getMessage());
    }

    static PollResult decodeFailed(DecodeFailure failure) {
        return new PollResult(Outcome.DECODE_FAILED, 0, Map.of(), null, 0, Duration.ZERO, failure.getMessage());
    }

    public boolean providerFailure() {
        return outcome == Outcome.FETCH_FAILED || outcome == Outcome.DECODE_FAILED;
    }
}
