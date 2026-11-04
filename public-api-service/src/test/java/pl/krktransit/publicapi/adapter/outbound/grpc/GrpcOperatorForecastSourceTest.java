package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastResponse;
import pl.krktransit.livevehicles.contracts.v1.StopForecast;
import pl.krktransit.publicapi.application.upcoming.OperatorForecast;
import pl.krktransit.publicapi.application.upcoming.OperatorForecast.ForecastedStop;
import pl.krktransit.publicapi.support.FakeUpstreams;

class GrpcOperatorForecastSourceTest {
    private static final String TRIP = "krakow:mpk-bus:t";
    private final FakeUpstreams upstreams = startUpstreams();
    private final ManagedChannel channel = upstreams.channel();
    private final GrpcOperatorForecastSource source =
            new GrpcOperatorForecastSource(channel, new GrpcCallSettings("live-token", Duration.ofSeconds(2)));

    @AfterEach
    void close() {
        channel.shutdownNow();
        upstreams.close();
    }

    @Test
    void asksForTheTripAndKeepsOptionalStopIdentity() {
        upstreams.liveVehicles.forecast = GetTripForecastResponse.newBuilder()
                .addStops(StopForecast.newBuilder().setStopSequence(0).setStopId("krakow:mpk-bus:a").setArrivalTime(seconds(100)))
                .addStops(StopForecast.newBuilder().setStopId("krakow:mpk-bus:b").setArrivalTime(seconds(200)))
                .addStops(StopForecast.newBuilder().setStopSequence(3).setArrivalTime(seconds(300))).build();

        assertThat(source.forecast(TRIP).stops()).containsExactly(
                new ForecastedStop(0, "krakow:mpk-bus:a", Instant.ofEpochSecond(100)),
                new ForecastedStop(null, "krakow:mpk-bus:b", Instant.ofEpochSecond(200)),
                new ForecastedStop(3, null, Instant.ofEpochSecond(300)));
        assertThat(upstreams.liveVehicles.forecastRequests).containsExactly(TRIP);
        assertThat(upstreams.authorizations).containsExactly("Bearer live-token");
    }

    @Test
    void stopsWithoutAnArrivalTimeAreIgnored() {
        upstreams.liveVehicles.forecast = GetTripForecastResponse.newBuilder()
                .addStops(StopForecast.newBuilder().setStopId("krakow:mpk-bus:a")).build();

        assertThat(source.forecast(TRIP)).isEqualTo(OperatorForecast.NONE);
    }

    @Test
    void anEmptyResponseHasNoForecast() {
        assertThat(source.forecast(TRIP)).isEqualTo(OperatorForecast.NONE);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "INVALID_ARGUMENT", "UNAUTHENTICATED", "INTERNAL", "UNIMPLEMENTED"})
    void everyFailureLeavesTheRowsWithoutAForecastAndTheNextAnswerIsUsedAgain(Status.Code code) {
        upstreams.liveVehicles.forecastFailure = code.toStatus();
        assertThat(source.forecast(TRIP)).isEqualTo(OperatorForecast.NONE);

        upstreams.liveVehicles.forecastFailure = null;
        upstreams.liveVehicles.forecast = GetTripForecastResponse.newBuilder()
                .addStops(StopForecast.newBuilder().setStopSequence(1).setArrivalTime(seconds(100))).build();
        assertThat(source.forecast(TRIP).stops()).hasSize(1);
    }

    private static Timestamp seconds(long value) { return Timestamp.newBuilder().setSeconds(value).build(); }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("forecast-" + UUID.randomUUID());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
