package pl.krktransit.schedule.adapter.outbound.http.exception;

import java.io.IOException;

public final class DownloadWriteFailure extends IOException {
    public DownloadWriteFailure(String message, IOException cause) {
        super(message, cause);
    }
}
