package pl.krktransit.schedule.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.application.importing.ImportFailure;

class DownloadRetryPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    private final DownloadRetryPolicy policy = new DownloadRetryPolicy(Duration.ofSeconds(5), Duration.ofSeconds(15), CLOCK);

    @Test
    void retriesOnlyTransientHttpStatuses() {
        List<Integer> retryable = IntStream.rangeClosed(100, 599).filter(policy::retryableStatus).boxed().toList();

        assertThat(retryable).containsExactly(408, 429, 500, 502, 503, 504);
    }

    @Test
    void increasesBackoffUntilTheConfiguredCap() {
        assertThat(policy.delay(1, headers())).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delay(2, headers())).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delay(3, headers())).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.delay(100, headers())).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void usesRetryAfterSecondsAsAMinimumDelay() {
        assertThat(policy.delay(1, retryAfter("12"))).isEqualTo(Duration.ofSeconds(12));
        assertThat(policy.delay(1, retryAfter("1"))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void resolvesHttpDateAgainstTheInjectedClock() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(CLOCK.instant().plusSeconds(12).atZone(ZoneOffset.UTC));

        assertThat(policy.delay(1, retryAfter(date))).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void pastOrMalformedRetryAfterFallsBackToBackoff() {
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(CLOCK.instant().minusSeconds(60).atZone(ZoneOffset.UTC));

        assertThat(policy.delay(1, retryAfter(past))).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delay(1, retryAfter("not an HTTP date"))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void refusesRetryAfterBeyondTheBudgetInsteadOfRetryingEarly() {
        assertThatThrownBy(() -> policy.delay(1, retryAfter("16")))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.DOWNLOAD))
                .hasMessageContaining("Retry-After");
    }

    private HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }

    private HttpHeaders retryAfter(String value) {
        return HttpHeaders.of(Map.of("Retry-After", List.of(value)), (name, header) -> true);
    }
}
