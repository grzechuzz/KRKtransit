package pl.krktransit.publicapi.bootstrap;

import java.util.List;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

final class PublicApiWebMvc implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    PublicApiWebMvc(List<String> allowedOrigins) { this.allowedOrigins = List.copyOf(allowedOrigins); }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(allowedOrigins.toArray(String[]::new)).allowedMethods("GET")
                .allowedHeaders("*");
    }
}
