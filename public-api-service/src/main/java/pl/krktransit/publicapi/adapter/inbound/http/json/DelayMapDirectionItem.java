package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record DelayMapDirectionItem(String headsign, long tripsCount, List<DelayMapStopItem> stops, List<DelayMapSegmentItem> segments) {
    public DelayMapDirectionItem { stops = List.copyOf(stops); segments = List.copyOf(segments); }
}
