package pl.krktransit.publicapi.bootstrap;

import io.grpc.ManagedChannel;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import pl.krktransit.publicapi.adapter.outbound.grpc.CachedScheduleSource;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcScheduleSource;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcStatisticsSource;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcVehiclePositionsSource;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.statistics.LineStatistics;
import pl.krktransit.publicapi.application.statistics.StatisticsSource;
import pl.krktransit.publicapi.application.upcoming.UpcomingStops;
import pl.krktransit.publicapi.application.vehicles.LiveVehicles;
import pl.krktransit.publicapi.application.vehicles.PositionSnapshot;
import pl.krktransit.publicapi.application.vehicles.VehiclePositionsSource;

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

    @Bean(destroyMethod = "shutdown")
    ManagedChannel liveVehiclesChannel(GrpcChannelFactory channels, PublicApiProperties properties) {
        return channels.createChannel(properties.liveVehicles().address());
    }

    @Bean
    StatisticsSource statisticsSource(ManagedChannel statisticsChannel, PublicApiProperties properties) {
        return new GrpcStatisticsSource(statisticsChannel, properties.statistics().calls());
    }

    @Bean
    ScheduleSource scheduleSource(ManagedChannel scheduleChannel, PublicApiProperties properties, Clock clock) {
        var settings = properties.schedule();
        var client = new GrpcScheduleSource(scheduleChannel, settings.calls(), settings.maximumConcurrentLookups());
        return new CachedScheduleSource(client, settings.tripCacheSize(), settings.tripStopsCacheSize(),
                settings.hashRefresh(), clock);
    }

    @Bean
    VehiclePositionsSource vehiclePositionsSource(ManagedChannel liveVehiclesChannel, PublicApiProperties properties) {
        return new GrpcVehiclePositionsSource(liveVehiclesChannel, properties.liveVehicles().calls());
    }

    @Bean
    LineStatistics lineStatistics(StatisticsSource statistics) { return new LineStatistics(statistics); }

    @Bean
    ScheduleLookups scheduleLookups(ScheduleSource schedule) { return new ScheduleLookups(schedule); }

    @Bean
    PositionSnapshot positionSnapshot(VehiclePositionsSource positions, PublicApiProperties properties, Clock clock) {
        return new PositionSnapshot(positions, properties.liveVehicles().snapshotLifetime(), clock);
    }

    @Bean
    LiveVehicles liveVehicles(PositionSnapshot positions, ScheduleSource schedule) {
        return new LiveVehicles(positions, schedule);
    }

    @Bean
    UpcomingStops upcomingStops(PositionSnapshot positions, ScheduleLookups schedule) {
        return new UpcomingStops(positions, schedule);
    }
}
