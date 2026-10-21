package pl.krktransit.schedule.adapter.outbound.http.exception;

import java.io.IOException;

public final class DownloadLimitExceeded extends IOException {
    public DownloadLimitExceeded() {
        super("Downloaded ZIP byte limit exceeded");
    }
}
