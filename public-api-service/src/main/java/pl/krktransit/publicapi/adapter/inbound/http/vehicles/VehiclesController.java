package pl.krktransit.publicapi.adapter.inbound.http.vehicles;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.LiveVehicleResponse;
import pl.krktransit.publicapi.application.vehicles.LiveVehicles;

@RestController
@RequestMapping("/v1/vehicles")
public class VehiclesController {
    private final LiveVehicles vehicles;
    private final VehicleResponseMapper mapper = new VehicleResponseMapper();

    public VehiclesController(LiveVehicles vehicles) { this.vehicles = vehicles; }

    @GetMapping("/positions")
    public LiveVehicleResponse positions() { return mapper.vehicles(vehicles.current()); }
}
