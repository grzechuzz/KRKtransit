package pl.krktransit.publicapi.adapter.inbound.http.trips;

import java.time.Instant;
import pl.krktransit.publicapi.adapter.inbound.http.ResponseTimes;
import pl.krktransit.publicapi.adapter.inbound.http.json.UpcomingStopItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.UpcomingStopsResponse;
import pl.krktransit.publicapi.application.upcoming.ArrivalSource;
import pl.krktransit.publicapi.application.upcoming.UpcomingStop;
import pl.krktransit.publicapi.application.upcoming.UpcomingTrip;

final class UpcomingStopsResponseMapper {
    UpcomingStopsResponse upcoming(UpcomingTrip trip) {
        return new UpcomingStopsResponse(trip.tripId(), trip.progress().name(), ResponseTimes.utc(trip.observedAt()),
                trip.stops().stream().map(this::stop).toList());
    }

    private UpcomingStopItem stop(UpcomingStop upcoming) {
        var stop = upcoming.stop();
        return new UpcomingStopItem(stop.stopId(), stop.stopName(), stop.stopDescription(), stop.sequence(),
                ResponseTimes.warsawWithOffset(upcoming.scheduledArrival()), predicted(upcoming.predictedArrival()),
                source(upcoming.predictionSource()));
    }

    private String predicted(Instant arrival) { return arrival == null ? null : ResponseTimes.warsawWithOffset(arrival); }

    private String source(ArrivalSource source) { return source == null ? null : source.name(); }
}
