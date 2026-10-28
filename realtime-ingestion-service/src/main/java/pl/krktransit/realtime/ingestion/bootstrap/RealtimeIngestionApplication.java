package pl.krktransit.realtime.ingestion.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeIngestionApplication {
    public static void main(String[] args) {
        SpringApplication.run(RealtimeIngestionApplication.class, args);
    }
}
