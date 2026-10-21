package pl.krktransit.schedule.application.port;

import pl.krktransit.schedule.application.importing.DownloadedFeed;

public interface ArchiveStore {
    void keep(DownloadedFeed download);
}
