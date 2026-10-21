package pl.krktransit.schedule.application.port;

import pl.krktransit.schedule.application.importing.DownloadedFeed;
import pl.krktransit.schedule.domain.feed.FeedConfig;

public interface FeedDownloader extends AutoCloseable {
    DownloadedFeed download(FeedConfig feed);
    @Override default void close() {}
}
