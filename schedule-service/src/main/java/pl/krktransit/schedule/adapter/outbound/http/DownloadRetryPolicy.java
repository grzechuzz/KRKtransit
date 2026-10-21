package pl.krktransit.schedule.adapter.outbound.http;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import pl.krktransit.schedule.application.importing.ImportFailure;

final class DownloadRetryPolicy {
    private final Duration initialBackoff;
    private final Duration maxDelay;
    private final Clock clock;

    DownloadRetryPolicy(Duration initialBackoff, Duration maxDelay, Clock clock) {
        this.initialBackoff = initialBackoff;
        this.maxDelay = maxDelay;
        this.clock = clock;
    }

    boolean retryableStatus(int status) {
        return switch (status) {
            case 408, 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    Duration delay(int attempt, HttpHeaders headers) {
        Duration serverDelay = retryAfter(headers).orElse(Duration.ZERO);
        validateServerDelay(serverDelay);
        Duration backoff = initialBackoff;
        for (int i = 1; i < attempt && backoff.compareTo(maxDelay) < 0; i++) {
            backoff = backoff.compareTo(maxDelay.dividedBy(2)) > 0 ? maxDelay : backoff.multipliedBy(2);
        }
        return backoff.compareTo(serverDelay) >= 0 ? backoff : serverDelay;
    }

    private void validateServerDelay(Duration serverDelay) {
        if (serverDelay.compareTo(maxDelay) > 0) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Retry-After exceeds the configured retry delay limit");
        }
    }

    private Optional<Duration> retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").flatMap(this::parseRetryAfter);
    }

    private Optional<Duration> parseRetryAfter(String value) {
        try {
            if (value.strip().matches("[0-9]+")) return Optional.of(Duration.ofSeconds(Long.parseLong(value.strip())));
            var requested = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), requested);
            return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
        } catch (DateTimeParseException | NumberFormatException failure) {
            return Optional.empty();
        }
    }
}
