package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pl.krktransit.realtime.ingestion.application.collection.FetchFailure;
import pl.krktransit.realtime.ingestion.application.port.FeedFetcher;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;

public final class HttpFeedFetcher implements FeedFetcher, AutoCloseable {
    private static final String USER_AGENT = "KRKTransit/1.0";
    private final HttpClient client;
    private final Duration downloadTimeout;
    private final int maxBodyBytes;
    private final int maxPerHost;
    private final Semaphore global;
    private final Map<String, Semaphore> hosts = new ConcurrentHashMap<>();
    private final RetryAfter retryAfter;

    public HttpFeedFetcher(HttpClient client, Duration downloadTimeout, int maxBodyBytes, int maxConcurrent,
                           int maxPerHost, Clock clock) {
        validateLimits(downloadTimeout, maxBodyBytes, maxConcurrent, maxPerHost);
        this.client = client;
        this.downloadTimeout = downloadTimeout;
        this.maxBodyBytes = maxBodyBytes;
        this.maxPerHost = maxPerHost;
        this.global = new Semaphore(maxConcurrent, true);
        this.retryAfter = new RetryAfter(clock);
    }

    private static void validateLimits(Duration downloadTimeout, int maxBodyBytes, int maxConcurrent, int maxPerHost) {
        if (!hasValidDownloadLimits(downloadTimeout, maxBodyBytes)
                || !hasValidConcurrencyLimits(maxConcurrent, maxPerHost)) {
            throw new IllegalArgumentException("Invalid realtime HTTP limits");
        }
    }

    private static boolean hasValidDownloadLimits(Duration downloadTimeout, int maxBodyBytes) {
        return downloadTimeout.isPositive() && maxBodyBytes > 0;
    }

    private static boolean hasValidConcurrencyLimits(int maxConcurrent, int maxPerHost) {
        return maxConcurrent > 0 && maxPerHost > 0;
    }

    @Override
    public byte[] fetch(Endpoint endpoint) {
        Semaphore host = hosts.computeIfAbsent(endpoint.url().getHost(), ignored -> new Semaphore(maxPerHost, true));
        acquire(host, endpoint);
        try {
            return downloadWithGlobalPermit(endpoint);
        } finally {
            host.release();
        }
    }

    private byte[] downloadWithGlobalPermit(Endpoint endpoint) {
        acquire(global, endpoint);
        try {
            return download(endpoint);
        } finally {
            global.release();
        }
    }

    private void acquire(Semaphore permits, Endpoint endpoint) {
        try {
            permits.acquire();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw cancelled(endpoint, failure);
        }
    }

    private byte[] download(Endpoint endpoint) {
        var request = HttpRequest.newBuilder(endpoint.url()).timeout(downloadTimeout)
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<byte[]> response = await(endpoint, client.sendAsync(request, new LimitedBodyHandler(maxBodyBytes)));
        return requireSuccess(response);
    }

    private HttpResponse<byte[]> await(Endpoint endpoint, CompletableFuture<HttpResponse<byte[]>> pending) {
        try {
            return pending.get(downloadTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled(endpoint, failure);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new FetchFailure(FetchFailure.Kind.TIMEOUT,
                    "complete download exceeded " + downloadTimeout, failure);
        } catch (CancellationException failure) {
            throw cancelled(endpoint, failure);
        } catch (ExecutionException failure) {
            throw translate(endpoint, failure.getCause());
        }
    }

    private static FetchFailure translate(Endpoint endpoint, Throwable cause) {
        if (cause instanceof BodyTooLarge) {
            return new FetchFailure(FetchFailure.Kind.BODY_TOO_LARGE, cause.getMessage(), cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return new FetchFailure(FetchFailure.Kind.TIMEOUT, cause.getMessage(), cause);
        }
        if (cause instanceof IOException) {
            return new FetchFailure(FetchFailure.Kind.TRANSPORT, cause.toString(), cause);
        }
        throw new IllegalStateException("Unexpected asynchronous HTTP failure for " + endpoint, cause);
    }

    private byte[] requireSuccess(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (LimitedBodyHandler.isSuccess(status)) {
            return response.body();
        }
        throw rejectedResponse(response);
    }

    private FetchFailure rejectedResponse(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        var kind = isAccessDenied(status) ? FetchFailure.Kind.ACCESS_DENIED : FetchFailure.Kind.HTTP_STATUS;
        return new FetchFailure(kind, status, retryAfter.requested(response.headers()),
                "HTTP " + status, null);
    }

    private static boolean isAccessDenied(int status) {
        return status == 401 || status == 403;
    }

    private static FetchFailure cancelled(Endpoint endpoint, Throwable cause) {
        return new FetchFailure(FetchFailure.Kind.CANCELLED, "fetch of " + endpoint + " cancelled", cause);
    }

    @Override
    public void close() {
        client.close();
    }
}
