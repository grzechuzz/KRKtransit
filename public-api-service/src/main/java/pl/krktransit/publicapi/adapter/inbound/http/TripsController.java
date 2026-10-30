package pl.krktransit.publicapi.adapter.inbound.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.TripStopsResponse;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;

@RestController
@RequestMapping("/v1/trips")
public class TripsController {
    private final ScheduleLookups schedule;
    private final ScheduleResponseMapper mapper = new ScheduleResponseMapper();

    public TripsController(ScheduleLookups schedule) { this.schedule = schedule; }

    @GetMapping("/{trip_id}/stops")
    public TripStopsResponse stops(@PathVariable("trip_id") @EntityId String tripId) {
        return mapper.tripStops(schedule.tripStops(tripId));
    }
}
