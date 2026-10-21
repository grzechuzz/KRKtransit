package pl.krktransit.schedule.adapter.inbound.scheduling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pl.krktransit.schedule.application.importing.*;
import pl.krktransit.schedule.application.port.*;
import pl.krktransit.schedule.domain.feed.*;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ImportCycleRunnerTest {
    @TempDir Path temporary;
    private final ScheduleHash hash = new ScheduleHash("a".repeat(64));
    private final List<FeedConfig> feeds = List.of(feed("mpk"), feed("mobilis"), feed("tram"));

    @Test
    void allDownloadsStartBeforeAwaitingAndEachCycleUsesFreshTasks() throws IOException {
        var arrivals = new CountDownLatch(3);
        var calls = new AtomicInteger();
        var store = new FakePublication();
        FeedDownloader downloader = config -> {
            calls.incrementAndGet();
            arrivals.countDown();
            await(arrivals);
            return downloaded(config);
        };
        var importer = new ImportFeed(store, ignored -> {}, (id, zip, sink) -> {});
        try (var runner = new ImportCycleRunner(feeds, downloader, importer, store, 3, 3)) {
            assertTrue(runner.runCycle().allOk());
            assertTrue(runner.runCycle().allOk());
        }
        assertEquals(6, calls.get());
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test
    void sourceFailureKeepsHealthyImportsAndCleansAllDownloads() throws IOException {
        var store = new FakePublication();
        FeedDownloader downloader = config -> {
            if (config.id().sourceId().equals("mobilis")) {
                throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "fixture failure");
            }
            return downloaded(config);
        };
        var importer = new ImportFeed(store, ignored -> {}, (id, zip, sink) -> {});
        try (var runner = new ImportCycleRunner(feeds, downloader, importer, store, 3, 3)) {
            var result = runner.runCycle();
            assertFalse(result.allOk());
            assertEquals(2, result.successfulFeeds());
            assertEquals(List.of(feeds.get(0).id(), feeds.get(2).id()), store.published);
        }
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test
    void unexpectedParserFailureIsNotConvertedIntoFeedFailureAndUnclaimedFilesAreCleaned() throws IOException {
        var arrivals = new CountDownLatch(3);
        var store = new FakePublication();
        FeedDownloader downloader = config -> {
            arrivals.countDown();
            await(arrivals);
            return downloaded(config);
        };
        var importer = new ImportFeed(store, ignored -> {}, (id, zip, sink) -> {
            throw new IllegalStateException("programming error");
        });
        try (var runner = new ImportCycleRunner(feeds, downloader, importer, store, 3, 3)) {
            assertThrows(IllegalStateException.class, runner::runCycle);
        }
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test
    void overlappingCycleIsSkippedAndShutdownInterruptsActualDownload() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new FakePublication();
        FeedDownloader downloader = config -> {
            started.countDown();
            try {
                release.await();
                throw new AssertionError("Download finished without cancellation");
            } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ImportFailure(ImportFailure.Kind.CANCELLED, "interrupted", failure);
            }
        };
        var importer = new ImportFeed(store, ignored -> {}, (id, zip, sink) -> {});
        var runner = new ImportCycleRunner(List.of(feeds.getFirst()), downloader, importer, store, 1, 1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var running = executor.submit(runner::runCycle);
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertTrue(runner.runCycle().skipped());
                runner.close();
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                var failure = assertThrows(ExecutionException.class, () -> running.get(3, TimeUnit.SECONDS));
                assertEquals(ImportFailure.Kind.CANCELLED,
                        assertInstanceOf(ImportFailure.class, failure.getCause()).kind());
            } finally {
                release.countDown();
                runner.close();
            }
        }
    }

    private DownloadedFeed downloaded(FeedConfig config) {
        try {
            var path = Files.createTempFile(temporary, config.id().sourceId(), ".zip");
            return new DownloadedFeed(path, hash);
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static FeedConfig feed(String source) {
        return new FeedConfig(new FeedId("krakow", source), URI.create("https://fixture.test/" + source));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS), "Downloads must overlap"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class FakePublication implements SchedulePublication {
        final List<FeedId> published = new ArrayList<>();
        public void registerFeeds(Collection<FeedId> ids) {}
        public Optional<ScheduleHash> currentHash(FeedId feed) { return Optional.empty(); }
        public PublicationOutcome publish(FeedId feed, ScheduleHash hash, Consumer<ScheduleWriter> load) {
            load.accept(null);
            published.add(feed);
            return PublicationOutcome.PUBLISHED;
        }
        public long purgeExpired(int size) { return 0; }
    }
}
