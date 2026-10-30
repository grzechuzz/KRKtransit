package pl.krktransit.publicapi.bootstrap;

import io.grpc.ManagedChannel;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import pl.krktransit.publicapi.adapter.outbound.grpc.CachedScheduleSource;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcScheduleSource;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcStatisticsSource;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.statistics.LineStatistics;
import pl.krktransit.publicapi.application.statistics.StatisticsSource;

@Configuration(proxyBeanMethods = false)
class PublicApiConfiguration {
    @Bean
    Clock publicApiClock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel statisticsChannel(GrpcChannelFactory channels, PublicApiProperties properties) {
        return channels.createChannel(properties.statistics().address());
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel scheduleChannel(GrpcChannelFactory channels, PublicApiProperties properties) {
        return channels.createChannel(properties.schedule().address());
    }

    @Bean
    StatisticsSource statisticsSource(ManagedChannel statisticsChannel, PublicApiProperties properties) {
        return new GrpcStatisticsSource(statisticsChannel, properties.statistics().calls());
    }

    @Bean
    ScheduleSource scheduleSource(ManagedChannel scheduleChannel, PublicApiProperties properties, Clock clock) {
        var settings = properties.schedule();
        var client = new GrpcScheduleSource(scheduleChannel, settings.calls());
        return new CachedScheduleSource(client, settings.hashRefresh(), clock);
    }

    @Bean
    LineStatistics lineStatistics(StatisticsSource statistics) { return new LineStatistics(statistics); }

    @Bean
    ScheduleLookups scheduleLookups(ScheduleSource schedule) { return new ScheduleLookups(schedule); }
}
