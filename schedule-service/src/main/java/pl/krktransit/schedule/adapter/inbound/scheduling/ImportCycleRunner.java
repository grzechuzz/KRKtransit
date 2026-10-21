package pl.krktransit.schedule.adapter.inbound.scheduling;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import pl.krktransit.schedule.application.importing.*;
import pl.krktransit.schedule.application.port.FeedDownloader;
import pl.krktransit.schedule.application.port.SchedulePublication;
import pl.krktransit.schedule.domain.feed.FeedConfig;

public final class ImportCycleRunner implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ImportCycleRunner.class.getName());
    private final List<FeedConfig> feeds;
    private final FeedDownloader downloader;
    private final ImportFeed importer;
    private final SchedulePublication publication;
    private final int maxDownloads;
    private final int perHost;
    private final ReentrantLock work = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    public ImportCycleRunner(List<FeedConfig> feeds, FeedDownloader downloader, ImportFeed importer,
                             SchedulePublication publication, int maxDownloads, int perHost) {
        if (maxDownloads < 1 || perHost < 1) throw new IllegalArgumentException("Download limits must be positive");
        if (feeds.stream().map(FeedConfig::id).distinct().count() != feeds.size()) {
            throw new IllegalArgumentException("Each configured feed identity must be unique");
        }
        this.feeds = List.copyOf(feeds);
        this.downloader = downloader;
        this.importer = importer;
        this.publication = publication;
        this.maxDownloads = maxDownloads;
        this.perHost = perHost;
    }

    public CycleResult runCycle() {
        if (closed.get() || !work.tryLock()) return new CycleResult(List.of(), true);
        worker = Thread.currentThread();
        try {
            requireRunning();
            publication.registerFeeds(feeds.stream().map(FeedConfig::id).toList());
            return importSources();
        } finally {
            worker = null;
            work.unlock();
        }
    }

    private CycleResult importSources() {
        var outcomes = new ArrayList<CycleResult.FeedOutcome>();
        try (var batch = new DownloadBatch(feeds, downloader, maxDownloads, perHost)) {
            for (int index = 0; index < feeds.size(); index++) {
                requireRunning();
                outcomes.add(importOne(feeds.get(index), batch.await(index), batch));
            }
        }
        return new CycleResult(outcomes, false);
    }

    private CycleResult.FeedOutcome importOne(FeedConfig feed, DownloadBatch.Outcome outcome, DownloadBatch batch) {
        if (outcome.failure() != null) return failed(feed, outcome.failure());
        try {
            LOG.log(System.Logger.Level.INFO, "Importing feed {0}, hash {1}", feed.id(), outcome.download().hash().value());
            PublicationOutcome result = importer.execute(feed, outcome.download());
            LOG.log(System.Logger.Level.INFO, "Feed {0}, hash {1}: {2}", feed.id(), outcome.download().hash().value(), result);
            return new CycleResult.FeedOutcome(feed.id(), result, null);
        } catch (ImportFailure failure) {
            return failed(feed, failure);
        } finally {
            batch.release(outcome.download());
        }
    }

    private CycleResult.FeedOutcome failed(FeedConfig feed, ImportFailure failure) {
        if (failure.kind() == ImportFailure.Kind.CANCELLED) throw failure;
        LOG.log(System.Logger.Level.WARNING, "Feed " + feed.id() + " failed: " + failure.kind(), failure);
        return new CycleResult.FeedOutcome(feed.id(), null, failure);
    }

    public long cleanExpired(int batchSize) {
        if (closed.get() || !work.tryLock()) return 0;
        worker = Thread.currentThread();
        try {
            requireRunning();
            return publication.purgeExpired(batchSize);
        } finally {
            worker = null;
            work.unlock();
        }
    }

    private void requireRunning() {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "Schedule worker is shutting down");
        }
    }

    @Override
    public void close() {
        closed.set(true);
        Thread active = worker;
        if (active == Thread.currentThread()) return;
        if (active != null) active.interrupt();
        awaitWorkerCleanup();
    }

    private void awaitWorkerCleanup() {
        boolean interrupted = Thread.interrupted();
        try {
            if (work.tryLock(20, TimeUnit.SECONDS)) {
                work.unlock();
            } else {
                LOG.log(System.Logger.Level.WARNING, "Schedule worker has not finished shutdown within 20 seconds");
            }
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
