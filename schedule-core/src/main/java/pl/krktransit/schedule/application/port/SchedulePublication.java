package pl.krktransit.schedule.application.port;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;
import pl.krktransit.schedule.application.importing.PublicationOutcome;

public interface SchedulePublication {
    void registerFeeds(Collection<FeedId> feeds);
    Optional<ScheduleHash> currentHash(FeedId feed);
    PublicationOutcome publish(FeedId feed, ScheduleHash hash, Consumer<ScheduleWriter> load);
    long purgeExpired(int batchSize);
}
