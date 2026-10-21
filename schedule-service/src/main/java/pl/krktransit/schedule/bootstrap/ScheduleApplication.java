package pl.krktransit.schedule.bootstrap;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ScheduleProperties.class)
public class ScheduleApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--migrate")) {
            MigrationCommand.run(System.getenv());
            return;
        }
        SpringApplication.run(ScheduleApplication.class, args);
    }
}
