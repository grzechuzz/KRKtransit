package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

final class RetryAfter {
    private final Clock clock;

    RetryAfter(Clock clock) {
        this.clock = clock;
    }

    Duration requested(HttpHeaders headers) {
        return headers.firstValue("Retry-After").flatMap(this::parse).orElse(Duration.ZERO);
    }

    private Optional<Duration> parse(String value) {
        String trimmed = value.strip();
        try {
            if (isDelaySeconds(trimmed)) return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
            return Optional.of(untilDate(trimmed));
        } catch (DateTimeParseException | NumberFormatException failure) {
            return Optional.empty();
        }
    }

    private static boolean isDelaySeconds(String value) {
        return value.matches("[0-9]{1,18}");
    }

    private Duration untilDate(String value) {
        var requested = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        Duration delay = Duration.between(clock.instant(), requested);
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
