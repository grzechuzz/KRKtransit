package pl.krktransit.publicapi.adapter.inbound.http;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class ResponseTimes {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final String UTC_OFFSET = "+00:00";
    private static final int NANOS_PER_MICROSECOND = 1_000;

    private ResponseTimes() {}

    public static String warsawLocal(Instant time) { return withMicroseconds(LOCAL.format(time.atZone(WARSAW)), time); }

    public static String warsawWithOffset(Instant time) { return ISO_WITH_OFFSET.format(time.atZone(WARSAW)); }

    public static String utc(Instant time) {
        return withMicroseconds(ISO_SECONDS.format(time.atOffset(ZoneOffset.UTC)), time) + UTC_OFFSET;
    }

    private static String withMicroseconds(String seconds, Instant time) {
        int microseconds = time.getNano() / NANOS_PER_MICROSECOND;
        return microseconds == 0 ? seconds : seconds + "." + String.format("%06d", microseconds);
    }
}
