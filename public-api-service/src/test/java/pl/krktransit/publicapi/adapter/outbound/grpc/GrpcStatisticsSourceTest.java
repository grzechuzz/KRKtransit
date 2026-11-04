package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.statistics.Punctuality;
import pl.krktransit.publicapi.application.statistics.SegmentDelay;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;
import pl.krktransit.publicapi.application.statistics.TrendDay;
import pl.krktransit.publicapi.support.FakeUpstreams;
import pl.krktransit.stopevents.contracts.v1.BandDelay;
import pl.krktransit.stopevents.contracts.v1.DelayBand;
import pl.krktransit.stopevents.contracts.v1.DelayMapResponse;
import pl.krktransit.stopevents.contracts.v1.DelayMapSegment;
import pl.krktransit.stopevents.contracts.v1.MaxDelayResponse;
import pl.krktransit.stopevents.contracts.v1.PunctualityResponse;
import pl.krktransit.stopevents.contracts.v1.TrendResponse;

class GrpcStatisticsSourceTest {
    private static final StatisticsQuery QUERY =
            new StatisticsQuery("194", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"), true);
    private static final Instant PLANNED = Instant.parse("2026-10-01T12:00:00Z");
    private final FakeUpstreams upstreams = startUpstreams();
    private final ManagedChannel channel = upstreams.channel();
    private final GrpcStatisticsSource source = new GrpcStatisticsSource(channel, new GrpcCallSettings("", Duration.ofSeconds(2)));

    @AfterEach
    void close() {
        channel.shutdownNow();
        upstreams.close();
    }

    @Test
    void mapsDelayMapWithoutInventingMissingBandsAndPreservesNegativeValues() {
        upstreams.statistics.delayMap = DelayMapResponse.newBuilder()
                .addSegments(DelayMapSegment.newBuilder()
                        .setFromStopKey("a|01").setToStopKey("b|")
                        .addDelays(BandDelay.newBuilder()
                                .setBand(DelayBand.ALL_DAY).setSamples(3)
                                .setMeanSeconds(-10.5).setMedianSeconds(-11).setP90Seconds(1.2))).build();
        var result = source.delayMap(QUERY);
        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.fromStopKey()).isEqualTo("a|01");
            assertThat(segment.delays()).containsExactly(new pl.krktransit.publicapi.application.statistics.BandDelay(
                    pl.krktransit.publicapi.application.statistics.DelayBand.ALL_DAY, 3, -10.5, -11, 1.2));
        });
        assertThat(upstreams.statistics.lastRequest.getIncludeEstimated()).isTrue();
        upstreams.statistics.failure = Status.NOT_FOUND;
        assertThatThrownBy(() -> source.delayMap(QUERY)).isInstanceOf(ApiFailure.NotFound.class);
        upstreams.statistics.failure = Status.DEADLINE_EXCEEDED;
        assertThatThrownBy(() -> source.delayMap(QUERY)).isInstanceOf(ApiFailure.UpstreamUnavailable.class);
    }

    @Test
    void sendsTheQueryAndMapsRankingsAndAggregates() {
        upstreams.statistics.maxDelay = MaxDelayResponse.newBuilder().setTripsAnalyzed(42)
                .addMaxDelay(pl.krktransit.stopevents.contracts.v1.SegmentDelay.newBuilder().setTripId("krakow:mpk-bus:1")
                        .setServiceDate("2026-10-01").setLineNumber("194").setVehicleNumber("DN041").setFromStop("A")
                        .setToStop("B").setFromSequence(2).setToSequence(3).setFromPlannedTime(timestamp(PLANNED))
                        .setFromEventTime(timestamp(PLANNED.plusSeconds(60))).setToPlannedTime(timestamp(PLANNED.plusSeconds(120)))
                        .setToEventTime(timestamp(PLANNED.plusSeconds(300))).setDelayGeneratedSeconds(120)
                        .setHeadsign("Krowodrza").setIsEstimated(true)).build();
        upstreams.statistics.punctuality = PunctualityResponse.newBuilder().setTotalStops(10).setOnTimeCount(7)
                .setOnTimePercent(70.0).setSlightlyDelayedCount(2).setSlightlyDelayedPercent(20.0).setDelayedCount(1)
                .setDelayedPercent(10.0).build();
        upstreams.statistics.trend = TrendResponse.newBuilder().addDays(pl.krktransit.stopevents.contracts.v1.TrendDay
                .newBuilder().setDate("2026-10-01").setAvgDelaySeconds(81.5).setTripsCount(12)).build();

        var ranking = source.maxDelay(QUERY);

        assertThat(upstreams.statistics.lastRequest.getLineNumber()).isEqualTo("194");
        assertThat(upstreams.statistics.lastRequest.getStartDate()).isEqualTo("2026-10-01");
        assertThat(upstreams.statistics.lastRequest.getEndDate()).isEqualTo("2026-10-07");
        assertThat(upstreams.statistics.lastRequest.getIncludeEstimated()).isTrue();
        assertThat(ranking.tripsAnalyzed()).isEqualTo(42);
        assertThat(ranking.segments()).containsExactly(new SegmentDelay("krakow:mpk-bus:1", LocalDate.parse("2026-10-01"),
                "194", "DN041", "A", "B", 2, 3, PLANNED, PLANNED.plusSeconds(60), PLANNED.plusSeconds(120),
                PLANNED.plusSeconds(300), 120, "Krowodrza", true));
        assertThat(source.punctuality(QUERY)).isEqualTo(new Punctuality(10, 7, 70.0, 2, 20.0, 1, 10.0));
        assertThat(source.trend(QUERY).days()).containsExactly(new TrendDay(LocalDate.parse("2026-10-01"), 81.5, 12));
        assertThat(source.routeDelay(QUERY).routes()).isEmpty();
    }

    @Test
    void translatesStatusesIntoApiFailures() {
        upstreams.statistics.failure = Status.NOT_FOUND;
        assertThatThrownBy(() -> source.maxDelay(QUERY)).isInstanceOf(ApiFailure.NotFound.class).hasMessage("Line '194' not found");

        upstreams.statistics.failure = Status.INVALID_ARGUMENT.withDescription("end_date cannot be in the future");
        assertThatThrownBy(() -> source.trend(QUERY)).isInstanceOf(ApiFailure.InvalidRequest.class)
                .hasMessage("end_date cannot be in the future");

        upstreams.statistics.failure = Status.DEADLINE_EXCEEDED;
        assertThatThrownBy(() -> source.punctuality(QUERY)).isInstanceOf(ApiFailure.UpstreamUnavailable.class)
                .hasMessage("Statistics service is unavailable");

        upstreams.statistics.failure = Status.RESOURCE_EXHAUSTED;
        assertThatThrownBy(() -> source.routeDelay(QUERY)).isInstanceOf(ApiFailure.UpstreamBusy.class);

        upstreams.statistics.failure = Status.INTERNAL;
        assertThatThrownBy(() -> source.routeDelay(QUERY)).isInstanceOf(ApiFailure.UpstreamRejected.class);
    }

    private static Timestamp timestamp(Instant time) {
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build();
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("statistics-" + UUID.randomUUID());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
