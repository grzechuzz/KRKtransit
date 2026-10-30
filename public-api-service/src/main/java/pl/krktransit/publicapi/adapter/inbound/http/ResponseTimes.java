package pl.krktransit.publicapi.adapter.inbound.http;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ResponseTimes {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int NANOS_PER_MICROSECOND = 1_000;

    private ResponseTimes() {}

    static String warsawLocal(Instant time) { return withMicroseconds(LOCAL.format(time.atZone(WARSAW)), time); }

    private static String withMicroseconds(String seconds, Instant time) {
        int microseconds = time.getNano() / NANOS_PER_MICROSECOND;
        return microseconds == 0 ? seconds : seconds + "." + String.format("%06d", microseconds);
    }
}
