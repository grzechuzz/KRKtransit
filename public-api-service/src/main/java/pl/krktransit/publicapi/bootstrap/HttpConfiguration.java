package pl.krktransit.publicapi.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.krktransit.publicapi.adapter.inbound.http.error.ApiErrorAttributes;
import pl.krktransit.publicapi.adapter.inbound.http.error.ApiExceptionHandler;
import pl.krktransit.publicapi.adapter.inbound.http.ratelimit.ClientRateLimits;
import pl.krktransit.publicapi.adapter.inbound.http.HealthController;
import pl.krktransit.publicapi.adapter.inbound.http.ratelimit.RateLimitInterceptor;
import pl.krktransit.publicapi.adapter.inbound.http.trips.ShapesController;
import pl.krktransit.publicapi.adapter.inbound.http.statistics.StatisticsController;
import pl.krktransit.publicapi.adapter.inbound.http.trips.TripsController;
import pl.krktransit.publicapi.adapter.inbound.http.vehicles.VehiclesController;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.statistics.LineStatistics;
import pl.krktransit.publicapi.application.upcoming.UpcomingStops;
import pl.krktransit.publicapi.application.vehicles.LiveVehicles;

@Configuration(proxyBeanMethods = false)
class HttpConfiguration {
    @Bean
    StatisticsController statisticsController(LineStatistics statistics) { return new StatisticsController(statistics); }

    @Bean
    VehiclesController vehiclesController(LiveVehicles vehicles) { return new VehiclesController(vehicles); }

    @Bean
    TripsController tripsController(ScheduleLookups schedule, UpcomingStops upcoming) {
        return new TripsController(schedule, upcoming);
    }

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
