package pl.krktransit.livevehicles.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LiveVehiclesProperties.class)
public class LiveVehiclesApplication {
    public static void main(String[] args) { SpringApplication.run(LiveVehiclesApplication.class, args); }
}
