package pl.krktransit.schedule.adapter.outbound.http.exception;

import java.io.IOException;
import java.net.http.HttpHeaders;

public final class RetryableDownloadFailure extends IOException {
    private final HttpHeaders headers;

    public RetryableDownloadFailure(String message, Throwable cause, HttpHeaders headers) {
        super(message, cause);
        this.headers = headers;
    }

    public HttpHeaders headers() {
        return headers;
    }
}
