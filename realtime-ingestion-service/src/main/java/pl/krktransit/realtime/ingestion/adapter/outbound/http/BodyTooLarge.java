package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import java.io.IOException;

final class BodyTooLarge extends IOException {
    BodyTooLarge(int limit) {
        super("Response body exceeds " + limit + " bytes");
    }
}
