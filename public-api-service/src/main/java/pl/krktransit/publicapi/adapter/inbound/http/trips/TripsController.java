package pl.krktransit.publicapi.adapter.inbound.http.trips;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.TripStopsResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.UpcomingStopsResponse;
import pl.krktransit.publicapi.adapter.inbound.http.validation.EntityId;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.upcoming.UpcomingStops;

@RestController
@RequestMapping("/v1/trips")
public class TripsController {
    private final ScheduleLookups schedule;
    private final UpcomingStops upcoming;
    private final ScheduleResponseMapper mapper = new ScheduleResponseMapper();
    private final UpcomingStopsResponseMapper upcomingMapper = new UpcomingStopsResponseMapper();

    public TripsController(ScheduleLookups schedule, UpcomingStops upcoming) {
        this.schedule = schedule;
        this.upcoming = upcoming;
    }

    @GetMapping("/{trip_id}/stops")
    public TripStopsResponse stops(@PathVariable("trip_id") @EntityId String tripId) {
        return mapper.tripStops(schedule.tripStops(tripId));
    }

    @GetMapping("/{trip_id}/upcoming-stops")
    public UpcomingStopsResponse upcomingStops(@PathVariable("trip_id") @EntityId String tripId) {
        return upcomingMapper.upcoming(upcoming.forTrip(tripId));
    }
}
