package pl.krktransit.schedule.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pl.krktransit.schedule.adapter.outbound.http.exception.DownloadWriteFailure;

@Timeout(20)
class LimitedBodyHandlerComponentTest {
    @Test
    void waitsForAnOngoingWriteBeforeClosingTheOutput() throws Exception {
        try (var server = new MockWebServer(); var client = client()) {
            server.start();
            server.enqueue(new MockResponse.Builder().body("zip bytes").build());
            var output = new ControlledOutput();
            output.blockWrite = true;
            try (var handler = new LimitedBodyHandler(Path.of("unused.zip"), 1024, output)) {
                var response = client.sendAsync(request(server), handler);
                assertThat(output.writing.await(5, TimeUnit.SECONDS)).isTrue();
                var closeRequested = new CountDownLatch(1);
                var closed = new CompletableFuture<Void>();
                Thread closer = Thread.ofVirtual().start(() -> {
                    closeRequested.countDown();
                    try {
                        handler.close();
                        closed.complete(null);
                    } catch (IOException failure) {
                        closed.completeExceptionally(failure);
                    }
                });
                try {
                    assertThat(closeRequested.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> closed.get(200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    assertThat(output.isOpen()).isTrue();
                } finally {
                    output.allowWrite.countDown();
                    assertThat(closer.join(Duration.ofSeconds(5))).isTrue();
                }
                closed.get(5, TimeUnit.SECONDS);
                response.handle((result, failure) -> null).get(5, TimeUnit.SECONDS);
                assertThat(output.isOpen()).isFalse();
                assertThat(output.closedDuringWrite).isFalse();
            }
        }
    }

    @Test
    void closesOutputBeforeReportingATruncatedResponse() throws Exception {
        try (var server = new MockWebServer(); var client = client()) {
            server.start();
            server.enqueue(new MockResponse.Builder().body("partial").setHeader("Content-Length", "100")
                    .onResponseEnd(new SocketEffect.CloseSocket()).build());
            var output = new ControlledOutput();
            output.blockClose = true;
            try (var handler = new LimitedBodyHandler(Path.of("unused.zip"), 1024, output)) {
                var response = client.sendAsync(request(server), handler);
                try {
                    assertThat(output.closing.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(response.isDone()).as("failure waits until the output is closed").isFalse();
                } finally {
                    output.allowClose.countDown();
                }
                assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
                assertThat(output.isOpen()).isFalse();
            }
        }
    }

    @Test
    void identifiesAPlainWriteIOExceptionAndClosesTheOutput() throws Exception {
        try (var server = new MockWebServer(); var client = client()) {
            server.start();
            server.enqueue(new MockResponse.Builder().body("zip bytes").build());
            var output = new ControlledOutput();
            output.failWrite = true;
            try (var handler = new LimitedBodyHandler(Path.of("unused.zip"), 1024, output)) {
                assertThatThrownBy(() -> client.sendAsync(request(server), handler).get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(DownloadWriteFailure.class)
                        .hasRootCauseMessage("fixture disk failure");
                assertThat(output.isOpen()).isFalse();
            }
        }
    }

    @Test
    void identifiesACloseIOException() throws Exception {
        try (var server = new MockWebServer(); var client = client()) {
            server.start();
            server.enqueue(new MockResponse.Builder().body("zip bytes").build());
            var output = new ControlledOutput();
            output.failClose = true;
            try (var handler = new LimitedBodyHandler(Path.of("unused.zip"), 1024, output)) {
                assertThatThrownBy(() -> client.sendAsync(request(server), handler).get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(DownloadWriteFailure.class)
                        .hasRootCauseMessage("fixture close failure");
            }
        }
    }

    private HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    private HttpRequest request(MockWebServer server) {
        return HttpRequest.newBuilder(server.url("/feed.zip").uri()).build();
    }

    private static final class ControlledOutput implements WritableByteChannel {
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final CountDownLatch closing = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch allowWrite = new CountDownLatch(1);
        private final AtomicBoolean activeWrite = new AtomicBoolean();
        private boolean blockWrite;
        private boolean blockClose;
        private boolean failWrite;
        private boolean failClose;
        private boolean closedDuringWrite;

        @Override
        public int write(ByteBuffer buffer) throws IOException {
            if (failWrite) throw new IOException("fixture disk failure");
            activeWrite.set(true);
            writing.countDown();
            try {
                awaitGate(blockWrite, allowWrite);
                int count = buffer.remaining();
                buffer.position(buffer.limit());
                return count;
            } finally {
                activeWrite.set(false);
            }
        }

        @Override public boolean isOpen() { return open.get(); }

        @Override
        public void close() throws IOException {
            closedDuringWrite = activeWrite.get();
            closing.countDown();
            awaitGate(blockClose, allowClose);
            open.set(false);
            if (failClose) throw new IOException("fixture close failure");
        }

        private void awaitGate(boolean blocked, CountDownLatch release) throws IOException {
            try {
                if (blocked && !release.await(10, TimeUnit.SECONDS)) throw new IOException("Output gate timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Output gate interrupted", failure);
            }
        }
    }
}
