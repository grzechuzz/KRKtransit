package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class PublicationBufferTest {
    @Test
    void admitsWholeResultsInOrderOnlyWhenTheyFit() throws Exception {
        var buffer = new PublicationBuffer(10);
        var first = record(0, 4);
        var second = record(1, 4);

        assertThat(buffer.offer(List.of(first, second))).isTrue();
        assertThat(buffer.offer(List.of(record(2, 1), record(3, 2)))).isFalse();
        assertThat(buffer.pendingBytes()).isEqualTo(8);
        assertThat(buffer.take()).isSameAs(first);
        assertThat(buffer.take()).isSameAs(second);
    }

    @Test
    void takenRecordsKeepTheirBytesPendingUntilReleased() throws Exception {
        var buffer = new PublicationBuffer(8);
        buffer.offer(List.of(record(0, 8)));
        var taken = buffer.take();

        assertThat(buffer.offer(List.of(record(1, 1)))).isFalse();
        buffer.release(taken);
        assertThat(buffer.offer(List.of(record(1, 1)))).isTrue();
    }

    @Test
    void acquisitionWaitsForRoomWithoutConsumingIt() throws Exception {
        var buffer = new PublicationBuffer(4);
        buffer.offer(List.of(record(0, 4)));
        var waiting = CompletableFuture.runAsync(() -> awaitRoom(buffer));

        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        buffer.release(buffer.take());
        waiting.get(5, TimeUnit.SECONDS);
        assertThat(buffer.pendingBytes()).isZero();
    }

    @Test
    void overflowPausesAcquisitionUntilHalfTheCapacityIsReleased() throws Exception {
        var buffer = new PublicationBuffer(10);
        buffer.offer(List.of(record(0, 3)));
        buffer.offer(List.of(record(1, 3)));
        buffer.offer(List.of(record(2, 3)));
        assertThat(buffer.offer(List.of(record(3, 2)))).isFalse();
        var waiting = CompletableFuture.runAsync(() -> awaitRoom(buffer));

        buffer.release(buffer.take());
        Thread.sleep(100);
        assertThat(waiting).isNotDone();
        buffer.release(buffer.take());
        waiting.get(5, TimeUnit.SECONDS);
    }

    @Test
    void overflowOfAnOversizedResultDoesNotPauseAMostlyEmptyBuffer() throws Exception {
        var buffer = new PublicationBuffer(10);
        buffer.offer(List.of(record(0, 2)));

        assertThat(buffer.offer(List.of(record(1, 9)))).isFalse();
        CompletableFuture.runAsync(() -> awaitRoom(buffer)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void closingWakesWaitersAndDrainsRemainingRecordsBeforeEnd() throws Exception {
        var buffer = new PublicationBuffer(4);
        var remaining = record(0, 2);
        buffer.offer(List.of(remaining));
        buffer.close();

        assertThat(buffer.offer(List.of(record(1, 1)))).isFalse();
        assertThat(buffer.take()).isSameAs(remaining);
        assertThat(buffer.take()).isNull();
    }

    @Test
    void discardingQueuedRecordsReleasesTheirBytes() {
        var buffer = new PublicationBuffer(10);
        buffer.offer(List.of(record(0, 3), record(1, 3)));

        assertThat(buffer.discardQueued()).isEqualTo(2);
        assertThat(buffer.pendingBytes()).isZero();
    }

    private static PendingRecord record(int stream, int valueBytes) {
        return new PendingRecord(new StreamTarget(new byte[0], stream + 1), new byte[valueBytes]);
    }

    private static void awaitRoom(PublicationBuffer buffer) {
        try {
            buffer.awaitRoom();
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }
}
