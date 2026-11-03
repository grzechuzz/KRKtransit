package pl.krktransit.stopevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import pl.krktransit.stopevents.adapter.inbound.grpc.StatisticsGrpcEndpoint;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcStatisticsReader;
import pl.krktransit.stopevents.adapter.outbound.postgres.JdbcStopEventStore;
import pl.krktransit.stopevents.adapter.outbound.redis.RedisStatisticsCache;
import pl.krktransit.stopevents.application.statistics.*;
import pl.krktransit.stopevents.bootstrap.MigrationCommand;
import pl.krktransit.stopevents.contracts.v1.*;
import pl.krktransit.stopevents.support.OwnedPostgres;
import pl.krktransit.stopevents.support.OwnedRedis;

@Testcontainers
class StatisticsServiceIT {
    @Container private static final OwnedPostgres DATABASE = new OwnedPostgres();
    @Container private static final OwnedRedis REDIS = new OwnedRedis();

    @Test
    void servesFourSqlBackedRpcsAndUsesRedisWhileKeepingOutagesDistinctFromMissingData() throws Exception {
        MigrationCommand.migrate(DATABASE.dataSource());
        new JdbcStopEventStore(DATABASE.dataSource()).insert(List.of(event(2, 60, BASE), event(3, 180, BASE.plusSeconds(600))));
        var sql = new JdbcStatisticsReader(DATABASE.dataSource(), Set.of(SOURCE));
        var reads = new AtomicInteger();
        var offline = new AtomicBoolean();
        StatisticsReader counted = (kind, query, cancellation) -> {
            reads.incrementAndGet();
            if (offline.get()) throw new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Owned fixture SQL unavailable");
            return sql.read(kind, query, cancellation);
        };
        var redis = RedisClient.create(REDIS.ownedUrl());
        try (var cache = new RedisStatisticsCache(() -> REDIS.connect(redis), "krktransit:stop-events:statistics:integration",
                8, 65536, Duration.ofMillis(500));
             var queries = new StatisticsQueries(counted, cache,
                     Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC), Executors.newVirtualThreadPerTaskExecutor(), 10);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var name = InProcessServerBuilder.generateName();
            var server = InProcessServerBuilder.forName(name).executor(executor)
                    .addService(new StatisticsGrpcEndpoint(queries)).build().start();
            var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            try {
                var client = StatisticsServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                var request = StatisticsRequest.newBuilder().setLineNumber("111").setStartDate(DATE.toString())
                        .setEndDate(DATE.toString()).setIncludeEstimated(true).build();
                assertThat(client.getMaxDelay(request).getMaxDelay(0).getDelayGeneratedSeconds()).isEqualTo(120);
                assertThat(client.getRouteDelay(request).getTripsAnalyzed()).isEqualTo(1);
                assertThat(client.getPunctuality(request).getTotalStops()).isEqualTo(2);
                assertThat(client.getTrend(request).getDays(0).getAvgDelaySeconds()).isEqualTo(120.0);
                assertThat(reads).hasValue(4);
                offline.set(true);
                assertThat(client.getMaxDelay(request).getTripsAnalyzed()).isEqualTo(1);
                assertThat(client.getRouteDelay(request).getTripsAnalyzed()).isEqualTo(1);
                assertThat(client.getPunctuality(request).getOnTimeCount()).isEqualTo(1);
                assertThat(client.getTrend(request).getDays(0).getTripsCount()).isEqualTo(1);
                assertThat(reads).hasValue(4);
                var unknown = request.toBuilder().setLineNumber("999").build();
                assertThatThrownBy(() -> client.getTrend(unknown)).isInstanceOfSatisfying(StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
                offline.set(false);
                assertThatThrownBy(() -> client.getTrend(unknown)).isInstanceOfSatisfying(StatusRuntimeException.class,
                        failure -> assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally { redis.shutdown(); }
    }
}
