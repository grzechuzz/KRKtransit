package pl.krktransit.publicapi.adapter.inbound.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.HealthResponse;

@RestController
public class HealthController {
    private static final HealthResponse HEALTHY = new HealthResponse("healthy");

    @GetMapping("/health")
    public HealthResponse health() { return HEALTHY; }
}
