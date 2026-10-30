package pl.krktransit.publicapi.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PublicApiProperties.class)
public class PublicApiApplication {
    public static void main(String[] args) { SpringApplication.run(PublicApiApplication.class, args); }
}
