package pl.krktransit.publicapi.adapter.inbound.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.ShapeResponse;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;

@RestController
@RequestMapping("/v1/shapes")
public class ShapesController {
    private final ScheduleLookups schedule;
    private final ScheduleResponseMapper mapper = new ScheduleResponseMapper();

    public ShapesController(ScheduleLookups schedule) { this.schedule = schedule; }

    @GetMapping("/{shape_id}")
    public ShapeResponse shape(@PathVariable("shape_id") @EntityId String shapeId) {
        return mapper.shape(schedule.shape(shapeId));
    }
}
