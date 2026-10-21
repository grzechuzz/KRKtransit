package pl.krktransit.schedule.application.importing;

import java.nio.file.Path;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public record DownloadedFeed(Path path, ScheduleHash hash) {}
