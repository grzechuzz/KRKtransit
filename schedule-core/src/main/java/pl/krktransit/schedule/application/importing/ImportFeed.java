package pl.krktransit.schedule.application.importing;

import pl.krktransit.schedule.application.port.ArchiveStore;
import pl.krktransit.schedule.application.port.FeedParser;
import pl.krktransit.schedule.application.port.SchedulePublication;
import pl.krktransit.schedule.domain.feed.FeedConfig;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class ImportFeed {
    private final SchedulePublication publication;
    private final ArchiveStore archives;
    private final FeedParser parser;

    public ImportFeed(SchedulePublication publication, ArchiveStore archives, FeedParser parser) {
        this.publication = publication;
        this.archives = archives;
        this.parser = parser;
    }

    public PublicationOutcome execute(FeedConfig feed, DownloadedFeed download) {
        if (isCurrentHash(feed.id(), download.hash())) {
            return PublicationOutcome.UNCHANGED;
        }
        archives.keep(download);
        return publication.publish(feed.id(), download.hash(),
                writer -> parser.parse(feed.id(), download.path(), writer));
    }

    private boolean isCurrentHash(FeedId feed, ScheduleHash hash) {
        return publication.currentHash(feed).filter(hash::equals).isPresent();
    }
}
