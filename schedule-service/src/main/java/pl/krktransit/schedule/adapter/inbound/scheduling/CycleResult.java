package pl.krktransit.schedule.adapter.inbound.scheduling;

import java.util.List;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.importing.PublicationOutcome;
import pl.krktransit.schedule.domain.feed.FeedId;

public record CycleResult(List<FeedOutcome> outcomes, boolean skipped) {
    public CycleResult { outcomes = List.copyOf(outcomes); }
    public boolean allOk() { return !skipped && outcomes.stream().noneMatch(item -> item.failure() != null); }
    public long successfulFeeds() { return outcomes.stream().filter(item -> item.failure() == null).count(); }
    public record FeedOutcome(FeedId feed, PublicationOutcome publication, ImportFailure failure) {}
}
