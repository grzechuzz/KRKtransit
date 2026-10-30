package pl.krktransit.publicapi.bootstrap;

import io.grpc.ManagedChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import pl.krktransit.publicapi.adapter.outbound.grpc.GrpcStatisticsSource;
import pl.krktransit.publicapi.application.statistics.LineStatistics;
import pl.krktransit.publicapi.application.statistics.StatisticsSource;

@Configuration(proxyBeanMethods = false)
class PublicApiConfiguration {
    @Bean(destroyMethod = "shutdown")
    ManagedChannel statisticsChannel(GrpcChannelFactory channels, PublicApiProperties properties) {
        return channels.createChannel(properties.statistics().address());
    }

    @Bean
    StatisticsSource statisticsSource(ManagedChannel statisticsChannel, PublicApiProperties properties) {
        return new GrpcStatisticsSource(statisticsChannel, properties.statistics().calls());
    }

    @Bean
    LineStatistics lineStatistics(StatisticsSource statistics) { return new LineStatistics(statistics); }
}
