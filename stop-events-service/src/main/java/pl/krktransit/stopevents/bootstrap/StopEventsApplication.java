package pl.krktransit.stopevents.bootstrap;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StopEventsProperties.class)
public class StopEventsApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate")) {
            MigrationCommand.run(System.getenv());
            return;
        }
        SpringApplication.run(StopEventsApplication.class, args);
    }
}
