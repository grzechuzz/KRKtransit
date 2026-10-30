package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.VehiclePosition;
import pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.vehicles.StopProgress;
import pl.krktransit.publicapi.support.FakeUpstreams;

class GrpcVehiclePositionsSourceTest {
    private static final Instant OBSERVED = Instant.parse("2026-10-30T10:00:00Z");
    private final FakeUpstreams upstreams = startUpstreams();
    private final ManagedChannel channel = upstreams.channel();
    private final GrpcVehiclePositionsSource source =
            new GrpcVehiclePositionsSource(channel, new GrpcCallSettings("vehicles-token", Duration.ofSeconds(2)));

    @AfterEach
    void close() {
        channel.shutdownNow();
        upstreams.close();
    }

    @Test
    void keepsOnlyPositionsThatCanBeShownOnTheMap() {
        upstreams.liveVehicles.positions = ListVehiclePositionsResponse.newBuilder()
                .addVehicles(complete().setBearing(0))
                .addVehicles(complete().clearBearing().setLicensePlate("DN002"))
                .addVehicles(complete().clearLicensePlate())
                .addVehicles(complete().setLicensePlate(" "))
                .addVehicles(complete().clearTripId())
                .addVehicles(complete().clearTimestamp())
                .addVehicles(complete().clearLatitude())
                .addVehicles(complete().clearSourceId()).build();

        var positions = source.positions();

        assertThat(positions).containsExactly(
                new pl.krktransit.publicapi.application.vehicles.VehiclePosition(new FeedId("krakow", "mpk-bus"),
                        "krakow:mpk-bus:1", "DN041", 0, 19.9, 0.0, OBSERVED, StopProgress.NONE),
                new pl.krktransit.publicapi.application.vehicles.VehiclePosition(new FeedId("krakow", "mpk-bus"),
                        "krakow:mpk-bus:1", "DN002", 0, 19.9, null, OBSERVED, StopProgress.NONE));
        assertThat(upstreams.authorizations).containsExactly("Bearer vehicles-token");
    }

    @Test
    void stopProgressIsMappedWithAbsenceAndUnrecognizedStatusesKeptApart() {
        upstreams.liveVehicles.positions = ListVehiclePositionsResponse.newBuilder()
                .addVehicles(complete().setCurrentStopSequence(0).setCurrentStatus(VehicleStopStatus.VEHICLE_STOP_STATUS_INCOMING_AT))
                .addVehicles(complete().setCurrentStopSequence(4).setCurrentStatus(VehicleStopStatus.VEHICLE_STOP_STATUS_STOPPED_AT))
                .addVehicles(complete().setCurrentStatus(VehicleStopStatus.VEHICLE_STOP_STATUS_IN_TRANSIT_TO))
                .addVehicles(complete().setCurrentStopSequence(5).setCurrentStatus(VehicleStopStatus.VEHICLE_STOP_STATUS_UNRECOGNIZED))
                .addVehicles(complete().setCurrentStopSequence(6).setCurrentStatusValue(99))
                .addVehicles(complete().setCurrentStopSequence(7)).build();

        assertThat(source.positions()).extracting(position -> position.progress()).containsExactly(
                new StopProgress(0, pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.INCOMING_AT),
                new StopProgress(4, pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.STOPPED_AT),
                new StopProgress(null, pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.IN_TRANSIT_TO),
                new StopProgress(5, pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.UNRECOGNIZED),
                new StopProgress(6, pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.UNRECOGNIZED),
                new StopProgress(7, null));
    }

    @Test
    void transportFailuresBecomeUpstreamFailures() {
        upstreams.liveVehicles.failure = Status.UNAVAILABLE;

        assertThatThrownBy(source::positions).isInstanceOf(ApiFailure.UpstreamUnavailable.class)
                .hasMessage("Live vehicles service is unavailable");
    }

    private static VehiclePosition.Builder complete() {
        return VehiclePosition.newBuilder().setCityId("krakow").setSourceId("mpk-bus").setTripId("krakow:mpk-bus:1")
                .setVehicleId("v1").setLicensePlate("DN041").setLatitude(0).setLongitude(19.9).setBearing(90)
                .setTimestamp(Timestamp.newBuilder().setSeconds(OBSERVED.getEpochSecond()));
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("vehicles-" + UUID.randomUUID());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
