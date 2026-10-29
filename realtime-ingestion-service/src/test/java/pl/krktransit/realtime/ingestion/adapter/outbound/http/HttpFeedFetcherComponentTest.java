package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.realtime.ingestion.application.collection.FetchFailure;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.domain.source.FeedKind;
import pl.krktransit.realtime.ingestion.support.Endpoints;

@Timeout(30)
class HttpFeedFetcherComponentTest {
    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException { server.close(); }

    @Test
    void sendsOneGetWithUserAgentAndReturnsTheCompleteBody() throws Exception {
        server.enqueue(new MockResponse.Builder().body(new Buffer().write(new byte[]{10, 3, 50, 46, 48})).build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThat(fetcher.fetch(endpoint("/vp.pb"))).hasValueSatisfying(body -> assertThat(body).containsExactly(10, 3, 50, 46, 48));

            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getTarget()).isEqualTo("/vp.pb");
            assertThat(request.getHeaders().get("User-Agent")).isEqualTo("KRKTransit/1.0 (krktransit.pl)");
        }
    }

    @Test
    void repeatsValidatorsAndReportsAnUnchangedFeedOn304() throws Exception {
        server.enqueue(new MockResponse.Builder().setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Wed, 28 Oct 2026 23:54:59 GMT").body("feed").build());
        server.enqueue(new MockResponse.Builder().code(304).build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThat(fetcher.fetch(endpoint("/vp.pb"))).isPresent();
            assertThat(fetcher.fetch(endpoint("/vp.pb"))).isEmpty();

            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("If-None-Match")).isNull();
            RecordedRequest conditional = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(conditional.getHeaders().get("If-None-Match")).isEqualTo("\"v1\"");
            assertThat(conditional.getHeaders().get("If-Modified-Since")).isEqualTo("Wed, 28 Oct 2026 23:54:59 GMT");
        }
    }

    @Test
    void forgottenValidatorsRequestTheFullFeedAgain() throws Exception {
        server.enqueue(new MockResponse.Builder().setHeader("ETag", "\"v1\"").body("truncated").build());
        server.enqueue(new MockResponse.Builder().setHeader("ETag", "\"v1\"").body("complete").build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            fetcher.fetch(endpoint("/vp.pb"));
            fetcher.forget(endpoint("/vp.pb"));
            assertThat(fetcher.fetch(endpoint("/vp.pb"))).isPresent();

            server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(server.takeRequest(5, TimeUnit.SECONDS).getHeaders().get("If-None-Match")).isNull();
        }
    }

    @Test
    void reportsHttp404WithRetryAfterWithoutAnImmediateRetry() {
        server.enqueue(new MockResponse.Builder().code(404).setHeader("Retry-After", "7").body("missing").build());
        server.enqueue(new MockResponse.Builder().body("must not be requested").build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThatThrownBy(() -> fetcher.fetch(endpoint("/tu.pb"))).isInstanceOfSatisfying(FetchFailure.class, failure -> {
                assertThat(failure.kind()).isEqualTo(FetchFailure.Kind.HTTP_STATUS);
                assertThat(failure.status()).isEqualTo(404);
                assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
            });
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void reportsAccessErrorsSeparately(int status) {
        server.enqueue(new MockResponse.Builder().code(status).build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThatThrownBy(() -> fetcher.fetch(endpoint("/vp.pb"))).isInstanceOfSatisfying(FetchFailure.class, failure -> {
                assertThat(failure.kind()).isEqualTo(FetchFailure.Kind.ACCESS_DENIED);
                assertThat(failure.status()).isEqualTo(status);
            });
        }
    }

    @Test
    void reportsServerErrorsAsHttpStatus() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThatThrownBy(() -> fetcher.fetch(endpoint("/vp.pb"))).isInstanceOfSatisfying(FetchFailure.class,
                    failure -> assertThat(failure.status()).isEqualTo(503));
        }
    }

    @Test
    void rejectsBodiesOverTheLimit() {
        server.enqueue(new MockResponse.Builder().chunkedBody(new Buffer().write(new byte[64 * 1024]), 4096).build());

        try (var fetcher = fetcher(Duration.ofSeconds(5), 16 * 1024, 3)) {
            assertThatThrownBy(() -> fetcher.fetch(endpoint("/vp.pb"))).isInstanceOfSatisfying(FetchFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(FetchFailure.Kind.BODY_TOO_LARGE));
        }
    }

    @Test
    void reportsConnectionFailuresAsTransport() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var unreachable = new Endpoint(Endpoints.MPK_BUS, FeedKind.VEHICLE_POSITIONS,
                URI.create("http://127.0.0.1:" + closedPort + "/vp.pb"));

        try (var fetcher = fetcher(Duration.ofSeconds(5), 1024, 3)) {
            assertThatThrownBy(() -> fetcher.fetch(unreachable)).isInstanceOfSatisfying(FetchFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(FetchFailure.Kind.TRANSPORT));
        }
    }

    @Test
    void deadlineCoversTheWholeBodyAndCancelsTheConnection() throws Exception {
        try (var body = new GatedResponseBody(); var fetcher = fetcher(Duration.ofSeconds(2), 8 * 1024 * 1024, 3)) {
            server.enqueue(new MockResponse.Builder().body(body).build());
            long started = System.nanoTime();

            assertThatThrownBy(() -> fetcher.fetch(endpoint("/vp.pb"))).isInstanceOfSatisfying(FetchFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(FetchFailure.Kind.TIMEOUT));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            body.close();
            body.awaitDisconnect();
        }
    }

    @Test
    void interruptionCancelsTheFetchAndKeepsTheInterruptFlag() throws Exception {
        try (var body = new GatedResponseBody(); var fetcher = fetcher(Duration.ofSeconds(20), 8 * 1024 * 1024, 3)) {
            server.enqueue(new MockResponse.Builder().body(body).build());
            var outcome = new CompletableFuture<FetchFailure>();
            var interruptFlag = new AtomicBoolean();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    fetcher.fetch(endpoint("/vp.pb"));
                } catch (FetchFailure failure) {
                    interruptFlag.set(Thread.currentThread().isInterrupted());
                    outcome.complete(failure);
                }
            });
            body.awaitStarted();
            worker.interrupt();

            assertThat(outcome.get(5, TimeUnit.SECONDS).kind()).isEqualTo(FetchFailure.Kind.CANCELLED);
            assertThat(interruptFlag).isTrue();
            body.close();
            body.awaitDisconnect();
        }
    }

    @Test
    void limitsActiveRequestsPerHost() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var body = new GatedResponseBody();
             var fetcher = fetcher(Duration.ofSeconds(20), 8 * 1024 * 1024, 1)) {
            server.enqueue(new MockResponse.Builder().body(body).build());
            server.enqueue(new MockResponse.Builder().body("second").build());
            var first = CompletableFuture.supplyAsync(() -> fetcher.fetch(endpoint("/vp.pb")), workers);
            body.awaitStarted();
            var second = CompletableFuture.supplyAsync(() -> fetcher.fetch(endpoint("/tu.pb")), workers);

            Thread.sleep(300);
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(second).isNotDone();
            body.close();

            assertThat(first.get(10, TimeUnit.SECONDS).orElseThrow()).hasSize(8 * 1024 * 513);
            assertThat(second.get(10, TimeUnit.SECONDS).orElseThrow()).hasSize(6);
        }
    }

    private HttpFeedFetcher fetcher(Duration timeout, int maxBodyBytes, int maxPerHost) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new HttpFeedFetcher(client, timeout, maxBodyBytes, 3, maxPerHost, Clock.systemUTC());
    }

    private Endpoint endpoint(String path) {
        return new Endpoint(Endpoints.MPK_BUS, FeedKind.VEHICLE_POSITIONS, server.url(path).uri());
    }
}
