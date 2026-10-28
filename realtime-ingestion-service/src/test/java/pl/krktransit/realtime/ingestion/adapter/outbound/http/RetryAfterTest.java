package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryAfterTest {
    private final RetryAfter retryAfter = new RetryAfter(
            Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void readsDelaySecondsAndHttpDates() {
        assertThat(retryAfter.requested(headers(" 600 "))).isEqualTo(Duration.ofSeconds(600));
        assertThat(retryAfter.requested(headers("Sun, 25 Oct 2026 12:02:30 GMT"))).isEqualTo(Duration.ofSeconds(150));
    }

    @Test
    void ignoresMissingInvalidAndPastValues() {
        assertThat(retryAfter.requested(HttpHeaders.of(Map.of(), (name, value) -> true))).isZero();
        assertThat(retryAfter.requested(headers("soon"))).isZero();
        assertThat(retryAfter.requested(headers("-5"))).isZero();
        assertThat(retryAfter.requested(headers("Sun, 25 Oct 2026 11:00:00 GMT"))).isZero();
    }

    private static HttpHeaders headers(String value) {
        return HttpHeaders.of(Map.of("Retry-After", List.of(value)), (name, header) -> true);
    }
}
