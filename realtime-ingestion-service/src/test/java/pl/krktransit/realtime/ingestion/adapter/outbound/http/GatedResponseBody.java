package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponseBody;
import okio.BufferedSink;

final class GatedResponseBody implements MockResponseBody, AutoCloseable {
    private static final int CHUNK_SIZE = 8 * 1024;
    private static final int REMAINING_CHUNKS = 512;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch disconnected = new CountDownLatch(1);

    @Override public long getContentLength() { return (long) CHUNK_SIZE * (REMAINING_CHUNKS + 1); }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        byte[] chunk = new byte[CHUNK_SIZE];
        sink.write(chunk);
        sink.flush();
        started.countDown();
        awaitRelease();
        try {
            for (int index = 0; index < REMAINING_CHUNKS; index++) {
                sink.write(chunk);
                sink.flush();
            }
        } catch (IOException closedSocket) {
            disconnected.countDown();
            throw closedSocket;
        }
    }

    void awaitStarted() throws InterruptedException {
        assertThat(started.await(10, TimeUnit.SECONDS)).as("server flushed the first response chunk").isTrue();
    }

    void awaitDisconnect() throws InterruptedException {
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).as("client cancelled the underlying connection").isTrue();
    }

    private void awaitRelease() throws IOException {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Response-body test gate was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Response-body test gate interrupted", failure);
        }
    }

    @Override public void close() { release.countDown(); }
}
