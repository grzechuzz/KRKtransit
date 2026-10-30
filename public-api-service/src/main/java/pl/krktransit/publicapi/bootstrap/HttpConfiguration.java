package pl.krktransit.publicapi.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.krktransit.publicapi.adapter.inbound.http.ApiErrorAttributes;
import pl.krktransit.publicapi.adapter.inbound.http.ApiExceptionHandler;
import pl.krktransit.publicapi.adapter.inbound.http.ShapesController;
import pl.krktransit.publicapi.adapter.inbound.http.StatisticsController;
import pl.krktransit.publicapi.adapter.inbound.http.TripsController;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.statistics.LineStatistics;

@Configuration(proxyBeanMethods = false)
class HttpConfiguration {
    @Bean
    StatisticsController statisticsController(LineStatistics statistics) { return new StatisticsController(statistics); }

    @Bean
    TripsController tripsController(ScheduleLookups schedule) { return new TripsController(schedule); }

    @Bean
    ShapesController shapesController(ScheduleLookups schedule) { return new ShapesController(schedule); }

    @Bean
    ApiExceptionHandler apiExceptionHandler() { return new ApiExceptionHandler(); }

    @Bean
    ApiErrorAttributes errorAttributes() { return new ApiErrorAttributes(); }
}
