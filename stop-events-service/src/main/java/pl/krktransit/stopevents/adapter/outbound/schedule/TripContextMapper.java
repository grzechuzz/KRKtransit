package pl.krktransit.stopevents.adapter.outbound.schedule;

import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.TripStop;
import pl.krktransit.stopevents.domain.schedule.ScheduledStop;
import pl.krktransit.stopevents.domain.schedule.TripContext;

final class TripContextMapper {
    TripContext map(GetTripContextResponse wire, String hash, String trip) {
        if (!consistent(wire, hash, trip)) {
            throw new IllegalStateException("Schedule returned an inconsistent trip context");
        }
        return new TripContext(hash, trip, wire.getLineNumber(), wire.hasDirectionId() ? wire.getDirectionId() : null,
                wire.hasHeadsign() ? wire.getHeadsign() : null, wire.getMaxStopSequence(),
                wire.getStopsList().stream().map(this::stop).toList());
    }

    private boolean consistent(GetTripContextResponse wire, String hash, String trip) {
        return wire.getHash().equals(hash) && wire.getTripId().equals(trip) && wire.hasMaxStopSequence();
    }

    private ScheduledStop stop(TripStop wire) {
        if (!complete(wire)) {
            throw new IllegalStateException("Schedule returned an incomplete stop occurrence");
        }
        return new ScheduledStop(wire.getStopSequence(), wire.getStopId(), wire.getStopName(),
                wire.hasStopDesc() ? wire.getStopDesc() : null, wire.getArrivalSeconds());
    }

    private boolean complete(TripStop wire) {
        return wire.hasStopSequence() && wire.hasArrivalSeconds() && wire.hasStopId() && wire.hasStopName();
    }
}
