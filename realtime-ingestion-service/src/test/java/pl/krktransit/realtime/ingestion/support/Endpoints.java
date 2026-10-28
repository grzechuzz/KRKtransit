package pl.krktransit.realtime.ingestion.support;

import java.net.URI;
import java.time.Instant;
import pl.krktransit.realtime.ingestion.domain.observation.ObservationOrigin;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.domain.source.FeedKind;
import pl.krktransit.realtime.ingestion.domain.source.SourceId;

public final class Endpoints {
    public static final SourceId MPK_BUS = new SourceId("krakow", "mpk-bus");
    public static final SourceId MPK_TRAM = new SourceId("krakow", "mpk-tram");
    public static final SourceId MOBILIS_BUS = new SourceId("krakow", "mobilis-bus");
    public static final Instant FETCHED_AT = Instant.parse("2026-10-25T10:15:30.123456789Z");

    private Endpoints() {}

    public static Endpoint vehiclePositions(SourceId source) {
        return new Endpoint(source, FeedKind.VEHICLE_POSITIONS, URI.create("https://feeds.test/" + source.sourceId() + "/vp.pb"));
    }

    public static Endpoint tripUpdates(SourceId source) {
        return new Endpoint(source, FeedKind.TRIP_UPDATES, URI.create("https://feeds.test/" + source.sourceId() + "/tu.pb"));
    }

    public static ObservationOrigin origin(SourceId source) {
        return new ObservationOrigin(source, FETCHED_AT, 1_761_387_300L);
    }
}
