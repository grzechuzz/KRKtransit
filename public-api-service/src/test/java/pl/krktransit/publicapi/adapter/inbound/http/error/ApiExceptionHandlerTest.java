package pl.krktransit.publicapi.adapter.inbound.http.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import pl.krktransit.publicapi.application.ApiFailure;

class ApiExceptionHandlerTest {
    private static final Logger LOGGER = Logger.getLogger(ApiExceptionHandler.class.getName());
    private static final Throwable CAUSE = new IllegalStateException("io exception");

    private final MovingClock clock = new MovingClock();
    private final ApiExceptionHandler handler = new ApiExceptionHandler(clock);
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) { records.add(record); }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    };

    @BeforeEach
    void captureLogs() { LOGGER.addHandler(capture); }

    @AfterEach
    void releaseLogs() { LOGGER.removeHandler(capture); }

    @Test
    void repeatedUpstreamFailureAnswersEveryRequestButLogsOnceWithItsCause() {
        for (int request = 0; request < 3; request++) {
            var response = handler.apiFailure(new ApiFailure.UpstreamUnavailable("Schedule service", CAUSE));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).isEqualTo("Schedule service is unavailable");
            assertThat(record.getThrown()).isSameAs(CAUSE);
        });
    }

    @Test
    void sameFailureIsLoggedAgainOnlyAfterTheInterval() {
        handler.apiFailure(new ApiFailure.UpstreamUnavailable("Schedule service", CAUSE));
        clock.advance(Duration.ofSeconds(59));
        handler.apiFailure(new ApiFailure.UpstreamUnavailable("Schedule service", CAUSE));
        assertThat(records).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        handler.apiFailure(new ApiFailure.UpstreamUnavailable("Schedule service", CAUSE));
        assertThat(records).hasSize(2);
    }

    @Test
    void differentFailuresAreLoggedIndependently() {
        handler.apiFailure(new ApiFailure.UpstreamUnavailable("Schedule service", CAUSE));
        handler.apiFailure(new ApiFailure.UpstreamUnavailable("Statistics service", CAUSE));
        handler.apiFailure(new ApiFailure.UpstreamBusy("Statistics service", CAUSE));

        assertThat(records).extracting(LogRecord::getMessage).containsExactly("Schedule service is unavailable",
                "Statistics service is unavailable", "Statistics service is busy");
    }

    @Test
    void repeatedRejectedRequestAnswersEveryRequestButLogsOneError() {
        for (int request = 0; request < 2; request++) {
            var response = handler.apiFailure(new ApiFailure.UpstreamRejected("Schedule service", CAUSE));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        assertThat(records).singleElement().satisfies(record -> assertThat(record.getLevel()).isEqualTo(Level.SEVERE));
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T08:50:00Z");

        void advance(Duration duration) { now = now.plus(duration); }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now; }
    }
}
