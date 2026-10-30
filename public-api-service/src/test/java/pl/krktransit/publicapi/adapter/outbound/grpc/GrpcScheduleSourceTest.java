package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.publicapi.support.FakeScheduleService.feedKey;
import static pl.krktransit.publicapi.support.FakeScheduleService.tripKey;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ShapePoint;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripSummary;
import pl.krktransit.publicapi.support.FakeUpstreams;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;

class GrpcScheduleSourceTest {
    private final FakeUpstreams upstreams = startUpstreams();
    private final ManagedChannel channel = upstreams.channel();
    private final GrpcScheduleSource source =
            new GrpcScheduleSource(channel, new GrpcCallSettings("schedule-token", Duration.ofSeconds(2)), 2);

    @AfterEach
    void close() {
        channel.shutdownNow();
        upstreams.close();
    }

    @Test
    void readsHashesTripsAndShapesWithOptionalValuesAndAuthentication() {
        upstreams.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "hash");
        upstreams.schedule.trips.put(tripKey("hash", "krakow:mpk-bus:1"), GetTripContextResponse.newBuilder()
                .setTripId("krakow:mpk-bus:1").setLineNumber("194").setHeadsign("Krowodrza")
                .addStops(pl.krktransit.schedule.contracts.v1.TripStop.newBuilder().setStopId("krakow:mpk-bus:s1")
                        .setStopName("Rondo").setStopDesc("01").setLatitude(50.1).setLongitude(19.9).setStopSequence(1))
                .addStops(pl.krktransit.schedule.contracts.v1.TripStop.newBuilder().setStopId("krakow:mpk-bus:s2")
                        .setStopName("Plac").setStopSequence(2)).build());
        upstreams.schedule.shapes.put(tripKey("hash", "krakow:mpk-bus:sh"), GetShapeResponse.newBuilder()
                .setShapeId("krakow:mpk-bus:sh").addPoints(pl.krktransit.schedule.contracts.v1.ShapePoint.newBuilder()
                        .setLatitude(0).setLongitude(19.5).setSequence(3)).build());

        assertThat(source.currentHash(new FeedId("krakow", "mpk-bus"))).contains("hash");
        assertThat(source.currentHash(new FeedId("krakow", "unknown"))).isEmpty();
        assertThat(source.tripStops("hash", "krakow:mpk-bus:1").orElseThrow().stops()).containsExactly(
                new TripStop("krakow:mpk-bus:s1", "Rondo", "01", 50.1, 19.9, 1),
                new TripStop("krakow:mpk-bus:s2", "Plac", null, null, null, 2));
        assertThat(source.shape("hash", "krakow:mpk-bus:sh").orElseThrow().points()).containsExactly(new ShapePoint(0, 19.5, 3));
        assertThat(source.shape("hash", "krakow:mpk-bus:missing")).isEmpty();
        assertThat(upstreams.authorizations).isNotEmpty().allMatch("Bearer schedule-token"::equals);
    }

    @Test
    void summariesSeparateFoundMissingAndUnresolvedTripsWithBoundedConcurrency() {
        var tripIds = IntStream.range(0, 12).mapToObj(index -> "krakow:mpk-bus:" + index).collect(Collectors.toSet());
        tripIds.forEach(tripId -> upstreams.schedule.trips.put(tripKey("hash", tripId),
                GetTripContextResponse.newBuilder().setLineNumber("1").setShapeId("krakow:mpk-bus:sh").build()));
        upstreams.schedule.trips.remove(tripKey("hash", "krakow:mpk-bus:0"));
        upstreams.schedule.tripFailures.put("krakow:mpk-bus:1", Status.UNAVAILABLE);
        upstreams.schedule.tripDelay = Duration.ofMillis(20);

        var summaries = source.tripSummaries("hash", tripIds);

        assertThat(summaries.missing()).containsExactly("krakow:mpk-bus:0");
        assertThat(summaries.found()).hasSize(10).containsEntry("krakow:mpk-bus:2", new TripSummary("1", null, "krakow:mpk-bus:sh"))
                .doesNotContainKey("krakow:mpk-bus:1");
        assertThat(upstreams.schedule.tripCalls).hasValue(12);
        assertThat(upstreams.schedule.maximumConcurrentTripCalls).hasValue(2);
    }

    @Test
    void translatesUnavailableDataAndTransportFailures() {
        upstreams.schedule.failure = Status.FAILED_PRECONDITION.withDescription("NOT_PUBLISHED");
        assertThatThrownBy(() -> source.currentHash(new FeedId("krakow", "mpk-bus")))
                .isInstanceOf(ApiFailure.UpstreamUnavailable.class).hasMessage("Schedule service is unavailable");

        upstreams.schedule.failure = Status.RESOURCE_EXHAUSTED;
        assertThatThrownBy(() -> source.tripStops("hash", "krakow:mpk-bus:1")).isInstanceOf(ApiFailure.UpstreamBusy.class);

        upstreams.schedule.failure = Status.UNAUTHENTICATED;
        assertThatThrownBy(() -> source.shape("hash", "krakow:mpk-bus:1")).isInstanceOf(ApiFailure.UpstreamRejected.class);
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("schedule-" + UUID.randomUUID());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
