package pl.krktransit.realtime.ingestion.support;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.VehicleStopStatus;

public final class GtfsRealtimeFeeds {
    private static final long FEED_TIMESTAMP = 1_761_387_300L;

    private GtfsRealtimeFeeds() {}

    public static byte[] vehiclePositions(String prefix, int vehicles) {
        var feed = header();
        for (int index = 0; index < vehicles; index++) {
            feed.addEntity(FeedEntity.newBuilder().setId("v" + index).setVehicle(VehiclePosition.newBuilder()
                    .setTrip(TripDescriptor.newBuilder().setTripId(prefix + "_trip_" + index))
                    .setVehicle(VehicleDescriptor.newBuilder().setId(prefix + "_" + index).setLicensePlate("KR " + index))
                    .setPosition(Position.newBuilder().setLatitude(50 + index / 10_000f).setLongitude(19.9f).setBearing(index % 360))
                    .setStopId("stop_" + index % 400).setCurrentStopSequence(index % 30)
                    .setCurrentStatus(VehicleStopStatus.forNumber(index % 3))
                    .setTimestamp(FEED_TIMESTAMP - index % 20)));
        }
        return feed.build().toByteArray();
    }

    public static byte[] tripUpdates(String prefix, int trips, int stopsPerTrip) {
        var feed = header();
        for (int index = 0; index < trips; index++) {
            var update = TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId(prefix + "_trip_" + index))
                    .setVehicle(VehicleDescriptor.newBuilder().setId(prefix + "_" + index)).setTimestamp(FEED_TIMESTAMP);
            for (int stop = 0; stop < stopsPerTrip; stop++) {
                update.addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopSequence(stop + 1).setStopId("stop_" + stop)
                        .setArrival(StopTimeEvent.newBuilder().setTime(FEED_TIMESTAMP + 60L * stop)));
            }
            feed.addEntity(FeedEntity.newBuilder().setId("t" + index).setTripUpdate(update));
        }
        return feed.build().toByteArray();
    }

    private static FeedMessage.Builder header() {
        return FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")
                .setIncrementality(FeedHeader.Incrementality.FULL_DATASET).setTimestamp(FEED_TIMESTAMP));
    }
}
