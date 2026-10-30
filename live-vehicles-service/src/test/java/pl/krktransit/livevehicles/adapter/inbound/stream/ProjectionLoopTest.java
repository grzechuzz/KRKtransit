package pl.krktransit.livevehicles.adapter.inbound.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryVehiclePositions;
import pl.krktransit.livevehicles.application.ObservationFailure;
import pl.krktransit.livevehicles.application.ObservationSource;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.VehiclePosition;

class ProjectionLoopTest {
    @Test
    void redisRecoveryStartsFromARecentConcreteCursorAndContinuesWithoutDetectorState() {
        var source = new SourceId("krakow", "mpk-bus");
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var input = new Input(source);
        var positions = new InMemoryVehiclePositions(Duration.ofMinutes(10), 10);
        var failure = new AtomicReference<Throwable>();
        try (var loop = new ProjectionLoop(input, positions, Set.of(source), Duration.ofMinutes(10),
                Duration.ofMillis(1), Duration.ofSeconds(2), Clock.fixed(now, java.time.ZoneOffset.UTC))) {
            var first = new VehiclePosition(source, source.scope("trip"), null, "A", 50, 20, null,
                    now.minusSeconds(1), now.minusSeconds(1), StreamId.parse("20-0"), StopProgress.NONE);
            var second = new VehiclePosition(source, source.scope("trip"), null, "A", 51, 21, null,
                    now, now, StreamId.parse("60-0"), StopProgress.NONE);
            input.results.add(new ObservationSource.ReadBatch(Map.of(source, "20-0"), List.of(first)));
            input.results.add(new ObservationFailure("disconnected", null));
            input.results.add(new ObservationSource.ReadBatch(Map.of(source, "60-0"), List.of(second)));
            loop.start((thread, error) -> failure.set(error));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(positions.snapshot(now)).containsExactly(second));
            assertThat(input.starts.get()).isEqualTo(2);
            assertThat(input.lastCursor.get()).isEqualTo("60-0");
            assertThat(failure.get()).isNull();
        }
    }

    private static final class Input implements ObservationSource {
        private final SourceId source;
        private final LinkedBlockingQueue<Object> results = new LinkedBlockingQueue<>();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicReference<String> lastCursor = new AtomicReference<>();
        private Input(SourceId source) { this.source = source; }
        @Override public Map<SourceId, String> recentCursors(Set<SourceId> sources, Duration lookback) {
            assertThat(lookback).isEqualTo(Duration.ofMinutes(10));
            return Map.of(source, starts.incrementAndGet() == 1 ? "10-0" : "50-0");
        }
        @Override public ReadBatch read(Map<SourceId, String> cursors) {
            lastCursor.set(cursors.get(source));
            try {
                var next = results.take();
                if (next instanceof ObservationFailure failure) throw failure;
                return (ReadBatch) next;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ObservationFailure("stopped", interrupted);
            }
        }
    }
}
