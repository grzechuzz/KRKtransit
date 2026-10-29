package pl.krktransit.schedule.adapter.outbound.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import pl.krktransit.schedule.adapter.outbound.http.exception.DownloadLimitExceeded;
import pl.krktransit.schedule.adapter.outbound.http.exception.DownloadWriteFailure;
import pl.krktransit.schedule.adapter.outbound.http.exception.RetryableDownloadFailure;
import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.FeedDownloader;
import pl.krktransit.schedule.domain.feed.FeedConfig;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class HttpFeedDownloader implements FeedDownloader {
    private static final System.Logger LOG = System.getLogger(HttpFeedDownloader.class.getName());
    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (key, value) -> true);
    private final HttpClient client;
    private final DownloadWorkspace workspace;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long maxDownloadBytes;
    private final DownloadRetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final Set<Path> pendingCleanup = ConcurrentHashMap.newKeySet();

    public HttpFeedDownloader(HttpClient client, Path tempDirectory, Duration requestTimeout, int maxAttempts,
                              Duration initialBackoff, Duration maxRetryDelay, long maxDownloadBytes) {
        this(client, tempDirectory, requestTimeout, maxAttempts, initialBackoff, maxRetryDelay,
                maxDownloadBytes, Clock.systemUTC(), Thread::sleep);
    }

    HttpFeedDownloader(HttpClient client, Path tempDirectory, Duration requestTimeout, int maxAttempts,
                       Duration initialBackoff, Duration maxRetryDelay, long maxDownloadBytes, Clock clock, Sleeper sleeper) {
        validateDownloadLimits(requestTimeout, maxAttempts, initialBackoff, maxRetryDelay, maxDownloadBytes);
        this.client = client;
        this.workspace = openWorkspace(tempDirectory);
        this.requestTimeout = requestTimeout;
        this.maxAttempts = maxAttempts;
        this.maxDownloadBytes = maxDownloadBytes;
        this.retryPolicy = new DownloadRetryPolicy(initialBackoff, maxRetryDelay, clock);
        this.sleeper = sleeper;
    }

    private static void validateDownloadLimits(Duration requestTimeout, int maxAttempts,
                                               Duration initialBackoff, Duration maxRetryDelay, long maxDownloadBytes) {
        if (requestTimeout.isNegative() || requestTimeout.isZero() || maxAttempts < 1 || maxDownloadBytes < 1
                || initialBackoff.isNegative() || maxRetryDelay.isNegative() || initialBackoff.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException("Invalid HTTP download limits");
        }
    }

    @Override
    public DownloadedFeed download(FeedConfig feed) {
        retryCleanup();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            checkCancellation();
            try {
                return attempt(feed);
            } catch (RetryableDownloadFailure failure) {
                if (attempt == maxAttempts) {
                    throw new ImportFailure(ImportFailure.Kind.DOWNLOAD,
                            "Download failed for " + feed.id() + " after " + attempt + " attempts: " + failure.getMessage(), failure);
                }
                pause(retryPolicy.delay(attempt, failure.headers()));
            }
        }
        throw new IllegalStateException("Download attempts exhausted without an outcome");
    }

    private DownloadedFeed attempt(FeedConfig feed) throws RetryableDownloadFailure {
        Path path = createTemporary();
        boolean claimed = false;
        try (var body = new LimitedBodyHandler(path, maxDownloadBytes)) {
            HttpResponse<Path> response = fetch(feed, body);
            requireSuccess(response);
            var downloaded = new DownloadedFeed(path, hash(path));
            claimed = true;
            return downloaded;
        } catch (DownloadWriteFailure failure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, failure.getMessage(), failure);
        } finally {
            if (!claimed) cleanup(path);
        }
    }

    private HttpResponse<Path> fetch(FeedConfig feed, LimitedBodyHandler body) throws RetryableDownloadFailure {
        var request = HttpRequest.newBuilder(feed.staticUrl()).timeout(requestTimeout)
                .header("User-Agent", "KRKTransit/1.0 (krktransit.pl)").GET().build();
        var pending = client.sendAsync(request, body);
        try {
            return pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "Download interrupted for " + feed.id(), failure);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new RetryableDownloadFailure("request/body deadline exceeded", failure, NO_HEADERS);
        } catch (ExecutionException failure) {
            throw translateFailure(failure.getCause());
        } catch (CancellationException failure) {
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "HTTP request cancelled", failure);
        }
    }

    private RetryableDownloadFailure translateFailure(Throwable cause) {
        if (cause instanceof DownloadLimitExceeded || cause instanceof DownloadWriteFailure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, cause.getMessage(), cause);
        }
        if (cause instanceof IOException) return new RetryableDownloadFailure("HTTP transport failed", cause, NO_HEADERS);
        throw new IllegalStateException("Unexpected asynchronous HTTP failure", cause);
    }

    private void requireSuccess(HttpResponse<Path> response) throws RetryableDownloadFailure {
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (retryPolicy.retryableStatus(status)) {
            throw new RetryableDownloadFailure("HTTP " + status, null, response.headers());
        }
        throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Feed download returned HTTP " + status);
    }

    private Path createTemporary() {
        try {
            return workspace.createTemporary();
        } catch (IOException failure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Cannot create temporary download", failure);
        }
    }

    private static DownloadWorkspace openWorkspace(Path directory) {
        try {
            return new DownloadWorkspace(directory);
        } catch (IOException failure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Cannot open temporary download workspace", failure);
        }
    }

    private ScheduleHash hash(Path path) {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancellation();
                digest.update(buffer, 0, count);
            }
            return new ScheduleHash(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Cannot hash downloaded ZIP", failure);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Java runtime must support SHA-256", failure);
        }
    }

    private void pause(Duration delay) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "HTTP retry interrupted", failure);
        }
    }

    private void cleanup(Path path) {
        pendingCleanup.add(path);
        deleteTemporary(path);
    }

    private void retryCleanup() {
        pendingCleanup.forEach(this::deleteTemporary);
    }

    private void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
            pendingCleanup.remove(path);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove temporary download {0}: {1}", path, failure.getMessage());
        }
    }

    private void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new ImportFailure(ImportFailure.Kind.CANCELLED, "Download interrupted");
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            retryCleanup();
            workspace.close();
        }
    }

    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
}
