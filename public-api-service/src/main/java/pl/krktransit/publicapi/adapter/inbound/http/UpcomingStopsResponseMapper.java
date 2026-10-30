package pl.krktransit.publicapi.adapter.inbound.http;

import pl.krktransit.publicapi.adapter.inbound.http.json.UpcomingStopItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.UpcomingStopsResponse;
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
                ResponseTimes.warsawWithOffset(upcoming.scheduledArrival()), null);
    }
}
