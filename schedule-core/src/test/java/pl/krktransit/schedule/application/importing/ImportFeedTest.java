package pl.krktransit.schedule.application.importing;

import org.junit.jupiter.api.Test;
import pl.krktransit.schedule.application.port.*;
import pl.krktransit.schedule.domain.feed.*;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ImportFeedTest {
    private final FeedConfig feed = new FeedConfig(new FeedId("krakow", "mpk"), URI.create("https://example.test/feed.zip"));
    private final ScheduleHash hash = new ScheduleHash("a".repeat(64));

    @Test
    void unchangedHashSkipsArchiveParsingAndPublication() {
        var store = new RecordingPublication(Optional.of(hash));
        var useCase = new ImportFeed(store, ignored -> fail("unchanged ZIP must not be archived again"),
                (id, zip, rows) -> fail("unchanged ZIP must not be parsed"));

        assertEquals(PublicationOutcome.UNCHANGED, useCase.execute(feed, new DownloadedFeed(Path.of("input.zip"), hash)));
        assertFalse(store.published);
    }

    @Test
    void archivesBeforeLoadingAndOnlyReturnsCommittedPublicationOutcome() {
        var steps = new ArrayList<String>();
        var store = new RecordingPublication(Optional.empty()) {
            @Override public PublicationOutcome publish(FeedId id, ScheduleHash value, Consumer<ScheduleWriter> load) {
                assertEquals(feed.id(), id);
                assertEquals(hash, value);
                load.accept(null);
                steps.add("commit");
                return PublicationOutcome.PUBLISHED;
            }
        };
        var useCase = new ImportFeed(store, ignored -> steps.add("archive"),
                (id, zip, rows) -> steps.add("parse"));

        assertEquals(PublicationOutcome.PUBLISHED, useCase.execute(feed, new DownloadedFeed(Path.of("input.zip"), hash)));
        assertEquals(List.of("archive", "parse", "commit"), steps);
    }

    @Test
    void propagatesTheOriginalPublicationFailure() {
        var original = new ImportFailure(ImportFailure.Kind.PERSISTENCE, "publication failed");
        var store = new RecordingPublication(Optional.empty()) {
            @Override public PublicationOutcome publish(FeedId id, ScheduleHash value, Consumer<ScheduleWriter> load) {
                load.accept(null);
                throw original;
            }
        };
        var useCase = new ImportFeed(store, ignored -> {}, (id, zip, rows) -> {});
        var failure = assertThrows(ImportFailure.class,
                () -> useCase.execute(feed, new DownloadedFeed(Path.of("input.zip"), hash)));
        assertSame(original, failure);
    }

    private static class RecordingPublication implements SchedulePublication {
        private final Optional<ScheduleHash> current;
        boolean published;
        RecordingPublication(Optional<ScheduleHash> current) { this.current = current; }
        public void registerFeeds(Collection<FeedId> feeds) {}
        public Optional<ScheduleHash> currentHash(FeedId feed) { return current; }
        public PublicationOutcome publish(FeedId feed, ScheduleHash hash, Consumer<ScheduleWriter> load) {
            published = true;
            load.accept(null);
            return PublicationOutcome.PUBLISHED;
        }
        public long purgeExpired(int batchSize) { return 0; }
    }
}
