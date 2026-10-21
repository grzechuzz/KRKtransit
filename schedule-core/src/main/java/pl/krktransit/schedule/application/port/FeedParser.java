package pl.krktransit.schedule.application.port;

import java.nio.file.Path;
import pl.krktransit.schedule.domain.feed.FeedId;

public interface FeedParser {
    void parse(FeedId feed, Path zip, ScheduleWriter writer);
}
