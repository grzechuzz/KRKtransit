package pl.krktransit.publicapi.bootstrap;

import java.util.List;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pl.krktransit.publicapi.adapter.inbound.http.RateLimitInterceptor;

final class PublicApiWebMvc implements WebMvcConfigurer {
    private final RateLimitInterceptor rateLimits;
    private final List<String> allowedOrigins;

    PublicApiWebMvc(RateLimitInterceptor rateLimits, List<String> allowedOrigins) {
        this.rateLimits = rateLimits;
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(rateLimits); }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(allowedOrigins.toArray(String[]::new)).allowedMethods("GET")
                .allowedHeaders("*");
    }
}
