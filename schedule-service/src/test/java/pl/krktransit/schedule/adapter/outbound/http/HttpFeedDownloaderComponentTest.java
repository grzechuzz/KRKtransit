package pl.krktransit.schedule.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.domain.feed.FeedConfig;
import pl.krktransit.schedule.domain.feed.FeedId;

@Timeout(20)
class HttpFeedDownloaderComponentTest {
    private static final String ZIP_BYTES = "fixture zip bytes";
    @TempDir Path temporary;
    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException { server.close(); }

    @Test
    void sendsGetWithUserAgentAndPreservesCompleteBodyAndSha256() throws Exception {
        server.enqueue(new MockResponse.Builder().body(ZIP_BYTES).build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            DownloadedFeed downloaded = downloader.download(feed());

            assertThat(Files.readAllBytes(downloaded.path())).isEqualTo(ZIP_BYTES.getBytes(StandardCharsets.UTF_8));
            assertThat(downloaded.hash().value()).isEqualTo("2a0b19afdfe6ba2d01a822bf194bf3a6e59fb0cf1dd907141a6f081a735bfd88");
            RecordedRequest request = takeRequest();
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getTarget()).isEqualTo("/feed.zip?source=fixture");
            assertThat(request.getHeaders().get("User-Agent")).isEqualTo("KRKTransit/1.0");
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertFileCount(1);
        }
    }

    @Test
    void doesNotRetry404AndRemovesItsTemporaryFile() throws IOException {
        server.enqueue(new MockResponse.Builder().code(404).body("missing").build());
        server.enqueue(new MockResponse.Builder().body("must not be requested").build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            assertThatThrownBy(() -> downloader.download(feed()))
                    .isInstanceOf(ImportFailure.class).hasMessageContaining("404");

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertFileCount(0);
        }
    }

    @Test
    void stopsAfterTheConfiguredNumberOfRetryableResponses() throws IOException {
        for (int index = 0; index < 3; index++) server.enqueue(new MockResponse.Builder().code(503).build());
        server.enqueue(new MockResponse.Builder().body("must not be requested").build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            assertThatThrownBy(() -> downloader.download(feed()))
                    .isInstanceOf(ImportFailure.class).hasMessageContaining("3 attempts").hasMessageContaining("503");

            assertThat(server.getRequestCount()).isEqualTo(3);
            assertFileCount(0);
        }
    }

    @Test
    void retries503WithRetryAfterAndReturnsTheSuccessfulBody() throws IOException {
        server.enqueue(new MockResponse.Builder().code(503).addHeader("Retry-After", "0").build());
        server.enqueue(new MockResponse.Builder().body(ZIP_BYTES).build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            assertThat(downloader.download(feed()).path()).hasContent(ZIP_BYTES);

            assertThat(server.getRequestCount()).isEqualTo(2);
            assertFileCount(1);
        }
    }

    @Test
    void refusesAnExcessiveRetryAfterWithoutAnotherRequest() throws IOException {
        server.enqueue(new MockResponse.Builder().code(429).addHeader("Retry-After", "3600").build());
        server.enqueue(new MockResponse.Builder().body("must not be requested").build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            assertThatThrownBy(() -> downloader.download(feed()))
                    .isInstanceOf(ImportFailure.class).hasMessageContaining("Retry-After");

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertFileCount(0);
        }
    }

    @Test
    void retriesAnInterruptedResponseAndDiscardsItsPartialFile() throws IOException {
        server.enqueue(new MockResponse.Builder().body("partial").setHeader("Content-Length", "100")
                .onResponseEnd(new SocketEffect.CloseSocket()).build());
        server.enqueue(new MockResponse.Builder().body(ZIP_BYTES).build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 1024)) {
            assertThat(downloader.download(feed()).path()).hasContent(ZIP_BYTES);

            assertThat(server.getRequestCount()).isEqualTo(2);
            assertFileCount(1);
        }
    }

    @Test
    void enforcesTheByteLimitOnChunkedResponsesWithoutRetrying() throws IOException {
        var body = new Buffer().write(new byte[128 * 1024]);
        server.enqueue(new MockResponse.Builder().chunkedBody(body, 4096).build());
        server.enqueue(new MockResponse.Builder().body("must not be requested").build());

        try (var downloader = downloader(Duration.ofSeconds(5), 3, 64 * 1024)) {
            assertThatThrownBy(() -> downloader.download(feed()))
                    .isInstanceOf(ImportFailure.class).hasMessageContaining("limit");

            assertThat(server.getRequestCount()).isEqualTo(1);
            assertFileCount(0);
        }
    }

    @Test
    void deadlineCoversTheResponseBodyAndCancelsTheUnderlyingConnection() throws Exception {
        try (var body = new GatedResponseBody(); var downloader = downloader(Duration.ofSeconds(3), 1, 8 * 1024 * 1024)) {
            server.enqueue(new MockResponse.Builder().body(body).build());
            var outcome = new CompletableFuture<ImportFailure>();
            Thread worker = downloadInBackground(downloader, outcome, new AtomicBoolean());
            try {
                body.awaitStarted();
                assertThat(outcome.get(10, TimeUnit.SECONDS)).satisfies(failure -> {
                    assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.DOWNLOAD);
                    assertThat(failure.getMessage()).contains("deadline");
                });
                assertFileCount(0);
                body.close();
                body.awaitDisconnect();
                assertThat(server.getRequestCount()).isEqualTo(1);
            } finally {
                stopWorker(worker, body);
            }
        }
    }

    @Test
    void interruptingADownloadCancelsTheConnectionAndPreservesTheInterruptFlag() throws Exception {
        try (var body = new GatedResponseBody(); var downloader = downloader(Duration.ofSeconds(30), 3, 8 * 1024 * 1024)) {
            server.enqueue(new MockResponse.Builder().body(body).build());
            var outcome = new CompletableFuture<ImportFailure>();
            var interrupted = new AtomicBoolean();
            Thread worker = downloadInBackground(downloader, outcome, interrupted);
            try {
                body.awaitStarted();
                worker.interrupt();

                assertThat(outcome.get(5, TimeUnit.SECONDS).kind()).isEqualTo(ImportFailure.Kind.CANCELLED);
                assertThat(interrupted).isTrue();
                assertFileCount(0);
                body.close();
                body.awaitDisconnect();
                assertThat(server.getRequestCount()).isEqualTo(1);
            } finally {
                stopWorker(worker, body);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesFailedTemporaryFileDeletion(boolean retryOnNextDownload) throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(temporary, PosixFileAttributeView.class) != null);
        try (var body = new GatedResponseBody(); var downloader = downloader(Duration.ofSeconds(30), 1, 8 * 1024 * 1024)) {
            Path directory = temporary.resolve(DownloadWorkspace.DIRECTORY_NAME);
            var permissions = Files.getPosixFilePermissions(directory);
            server.enqueue(new MockResponse.Builder().body(body).build());
            var outcome = new CompletableFuture<ImportFailure>();
            Thread worker = downloadInBackground(downloader, outcome, new AtomicBoolean());
            try {
                body.awaitStarted();
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
                Assumptions.assumeFalse(Files.isWritable(directory), "Deletion must be denied for this user");
                worker.interrupt();
                assertThat(outcome.get(5, TimeUnit.SECONDS).kind()).isEqualTo(ImportFailure.Kind.CANCELLED);
                assertFileCount(1);
            } finally {
                Files.setPosixFilePermissions(directory, permissions);
                stopWorker(worker, body);
            }
            if (retryOnNextDownload) {
                server.enqueue(new MockResponse.Builder().body(ZIP_BYTES).build());
                DownloadedFeed downloaded = downloader.download(feed());
                assertFileCount(1);
                Files.delete(downloaded.path());
            }
        }
        assertFileCount(0);
    }

    private Thread downloadInBackground(HttpFeedDownloader downloader, CompletableFuture<ImportFailure> outcome,
                                        AtomicBoolean interrupted) {
        return Thread.ofVirtual().start(() -> {
            try {
                downloader.download(feed());
                outcome.completeExceptionally(new AssertionError("A blocked download must not succeed"));
            } catch (ImportFailure failure) {
                interrupted.set(Thread.currentThread().isInterrupted());
                outcome.complete(failure);
            } catch (RuntimeException | Error unexpected) {
                outcome.completeExceptionally(unexpected);
            }
        });
    }

    private void stopWorker(Thread worker, GatedResponseBody body) throws InterruptedException {
        worker.interrupt();
        body.close();
        assertThat(worker.join(Duration.ofSeconds(5))).as("download worker terminated").isTrue();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("HTTP request received").isNotNull();
        return request;
    }

    private void assertFileCount(int expected) throws IOException {
        try (var files = Files.walk(temporary)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".zip"))).hasSize(expected);
        }
    }

    private FeedConfig feed() {
        return new FeedConfig(new FeedId("krakow", "test"), server.url("/feed.zip?source=fixture").uri());
    }

    private HttpFeedDownloader downloader(Duration timeout, int attempts, long maxBytes) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
        return new HttpFeedDownloader(client, temporary, timeout, attempts, Duration.ZERO, Duration.ofSeconds(2), maxBytes);
    }
}
