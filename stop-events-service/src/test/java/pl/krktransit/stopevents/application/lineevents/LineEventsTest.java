package pl.krktransit.stopevents.application.lineevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

class LineEventsTest {
    private static final LineEventsQuery QUERY = new LineEventsQuery("krakow", "139",
            Instant.parse("2026-10-25T12:00:00Z"), OptionalLong.empty(), 10);

    @Test
    void refusesReadsBeyondTheConcurrentLimitAndReleasesCapacityAfterward() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lineEvents = new LineEvents(query -> {
            entered.countDown();
            await(release);
            return new LineEventsPage(List.of(), false);
        }, 1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> lineEvents.list(QUERY));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> lineEvents.list(QUERY)).isInstanceOfSatisfying(LineEventsFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(LineEventsFailure.Kind.BUSY));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).events()).isEmpty();
        }
        assertThat(lineEvents.list(QUERY).hasMore()).isFalse();
    }

    @Test
    void releasesCapacityWhenTheReaderFails() {
        var lineEvents = new LineEvents(query -> {
            throw new LineEventsFailure(LineEventsFailure.Kind.UNAVAILABLE, "offline");
        }, 1);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> lineEvents.list(QUERY)).isInstanceOfSatisfying(LineEventsFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(LineEventsFailure.Kind.UNAVAILABLE));
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
