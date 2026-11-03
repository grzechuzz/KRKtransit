package pl.krktransit.schedule.domain.lineroute;

import java.util.Locale;

public record StopKey(String value) {
    public static StopKey of(String stopName, String stopDesc) {
        return new StopKey(stopName.toLowerCase(Locale.ROOT) + "|"
                + (stopDesc == null ? "" : stopDesc.toLowerCase(Locale.ROOT)));
    }
}
