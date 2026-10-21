package pl.krktransit.schedule.adapter.inbound.scheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.FeedDownloader;
import pl.krktransit.schedule.domain.feed.FeedConfig;

final class DownloadBatch implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DownloadBatch.class.getName());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Queue<Path> ownedFiles = new ConcurrentLinkedQueue<>();
    private final List<CompletableFuture<Outcome>> tasks;
    private final AtomicBoolean closed = new AtomicBoolean();

    DownloadBatch(List<FeedConfig> feeds, FeedDownloader downloader, int maximum, int perHost) {
        Semaphore global = new Semaphore(maximum);
        Map<String, Semaphore> hosts = new HashMap<>();
        feeds.forEach(feed -> hosts.computeIfAbsent(feed.staticUrl().getHost(), ignored -> new Semaphore(perHost)));
        tasks = feeds.stream().map(feed -> CompletableFuture.supplyAsync(
                () -> download(feed, downloader, global, hosts.get(feed.staticUrl().getHost())), executor)).toList();
    }

    Outcome await(int index) {
        try {
            return tasks.get(index).get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw cancelled(failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException error) throw error;
            throw new IllegalStateException("Unexpected checked download failure", failure.getCause());
        }
    }

    private Outcome download(FeedConfig feed, FeedDownloader downloader, Semaphore global, Semaphore host) {
        boolean hostAcquired = false;
        boolean globalAcquired = false;
        try {
            host.acquire();
            hostAcquired = true;
            global.acquire();
            globalAcquired = true;
            return new Outcome(downloadAndTrack(feed, downloader), null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Outcome(null, cancelled(failure));
        } catch (ImportFailure failure) {
            return new Outcome(null, failure);
        } finally {
            if (globalAcquired) global.release();
            if (hostAcquired) host.release();
        }
    }

    private DownloadedFeed downloadAndTrack(FeedConfig feed, FeedDownloader downloader) {
        if (closed.get()) throw cancelled(null);
        DownloadedFeed result = downloader.download(feed);
        ownedFiles.add(result.path());
        if (closed.get()) delete(result.path());
        return result;
    }

    void release(DownloadedFeed download) {
        if (delete(download.path())) ownedFiles.remove(download.path());
    }

    @Override
    public void close() {
        closed.set(true);
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Download workers are still finishing cancellation");
            }
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            ownedFiles.forEach(DownloadBatch::delete);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean delete(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        }
        catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove temporary download " + path, failure);
            return false;
        }
    }

    private static ImportFailure cancelled(Throwable cause) {
        return new ImportFailure(ImportFailure.Kind.CANCELLED, "Download cycle interrupted", cause);
    }

    record Outcome(DownloadedFeed download, ImportFailure failure) {}
}
