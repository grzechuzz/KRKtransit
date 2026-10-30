package pl.krktransit.publicapi.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.krktransit.publicapi.adapter.inbound.http.ApiErrorAttributes;
import pl.krktransit.publicapi.adapter.inbound.http.ApiExceptionHandler;
import pl.krktransit.publicapi.adapter.inbound.http.ClientRateLimits;
import pl.krktransit.publicapi.adapter.inbound.http.HealthController;
import pl.krktransit.publicapi.adapter.inbound.http.RateLimitInterceptor;
import pl.krktransit.publicapi.adapter.inbound.http.ShapesController;
import pl.krktransit.publicapi.adapter.inbound.http.StatisticsController;
import pl.krktransit.publicapi.adapter.inbound.http.TripsController;
import pl.krktransit.publicapi.adapter.inbound.http.VehiclesController;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.statistics.LineStatistics;
import pl.krktransit.publicapi.application.vehicles.LiveVehicles;

@Configuration(proxyBeanMethods = false)
class HttpConfiguration {
    @Bean
    StatisticsController statisticsController(LineStatistics statistics) { return new StatisticsController(statistics); }

    @Bean
    VehiclesController vehiclesController(LiveVehicles vehicles) { return new VehiclesController(vehicles); }

    @Bean
    TripsController tripsController(ScheduleLookups schedule) { return new TripsController(schedule); }

    @Bean
    ShapesController shapesController(ScheduleLookups schedule) { return new ShapesController(schedule); }

    @Bean
    HealthController healthController() { return new HealthController(); }

    @Bean
    ApiExceptionHandler apiExceptionHandler() { return new ApiExceptionHandler(); }

    @Bean
    ApiErrorAttributes errorAttributes() { return new ApiErrorAttributes(); }

    @Bean
    RateLimitInterceptor rateLimitInterceptor(PublicApiProperties properties) {
        var limits = properties.rateLimits();
        return new RateLimitInterceptor(new ClientRateLimits(limits.perMinuteByRoute(), limits.maximumTrackedClients()));
    }

    @Bean
    PublicApiWebMvc publicApiWebMvc(RateLimitInterceptor rateLimits, PublicApiProperties properties) {
        return new PublicApiWebMvc(rateLimits, properties.allowedOrigins());
    }
}
