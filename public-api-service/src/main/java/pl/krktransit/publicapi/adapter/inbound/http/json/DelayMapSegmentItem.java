package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record DelayMapSegmentItem(String fromStopKey, String toStopKey, List<List<Double>> points, List<BandDelayItem> delays) {
    public DelayMapSegmentItem {
        points = points.stream().map(List::copyOf).toList();
        delays = List.copyOf(delays);
    }
}
