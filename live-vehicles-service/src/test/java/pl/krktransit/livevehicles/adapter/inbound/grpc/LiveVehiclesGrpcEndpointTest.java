package pl.krktransit.livevehicles.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryTripForecasts;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryVehiclePositions;
import pl.krktransit.livevehicles.application.LiveVehicleQueries;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastRequest;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastResponse;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StopForecast;
import pl.krktransit.livevehicles.domain.StopProgress;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.TripForecast;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.domain.VehicleStopStatus;
import pl.krktransit.livevehicles.support.MutableClock;

class LiveVehiclesGrpcEndpointTest {
    @Test
    void authenticatedRpcReturnsEveryRetainedPositionAndPreservesOptionalValues() throws Exception {
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var clock = new MutableClock(now);
        var positions = new InMemoryVehiclePositions(Duration.ofMinutes(10), 10);
        var source = new SourceId("krakow", "mpk-bus");
        positions.accept(new VehiclePosition(source, source.scope("trip"), null, "A", 0, 0, null,
                now.minusSeconds(300), now, StreamId.parse("1-0"), StopProgress.NONE), now);
        positions.accept(new VehiclePosition(source, null, "vehicle", null, 50, 20, 0.0, null, now, StreamId.parse("2-0"), StopProgress.NONE), now);
        var queries = new LiveVehicleQueries(positions, noForecasts(), clock);
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(
                new LiveVehiclesGrpcEndpoint(queries), new BearerTokenInterceptor("test-token"))).build().start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var anonymous = LiveVehiclesServiceGrpc.newBlockingStub(channel);
            assertThatThrownBy(() -> anonymous.listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, failure ->
                            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            var headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer test-token");
            var stub = anonymous.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            var result = stub.listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance());
            assertThat(result.getVehiclesList()).hasSize(2);
            assertThat(result.getVehicles(0)).satisfies(position -> {
                assertThat(position.getVehicleId()).isEqualTo("vehicle");
                assertThat(position.hasTripId()).isFalse();
                assertThat(position.hasLicensePlate()).isFalse();
                assertThat(position.hasTimestamp()).isFalse();
                assertThat(position.hasBearing()).isTrue();
                assertThat(position.getBearing()).isZero();
                assertThat(position.getFetchedAt().getSeconds()).isEqualTo(now.getEpochSecond());
            });
            assertThat(result.getVehicles(1)).satisfies(position -> {
                assertThat(position.getTripId()).isEqualTo(source.scope("trip"));
                assertThat(position.getLicensePlate()).isEqualTo("A");
                assertThat(position.hasLatitude()).isTrue();
                assertThat(position.getLatitude()).isZero();
                assertThat(position.hasBearing()).isFalse();
                assertThat(position.hasVehicleId()).isFalse();
                assertThat(position.getTimestamp().getSeconds()).isEqualTo(now.minusSeconds(300).getEpochSecond());
            });
            clock.advance(Duration.ofMinutes(5));
            assertThat(stub.listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance()).getVehiclesList())
                    .extracting(pl.krktransit.livevehicles.contracts.v1.VehiclePosition::getVehicleId).containsExactly("vehicle");
        } finally { channel.shutdownNow(); server.shutdownNow(); }
    }

    @Test
    void stopProgressIsReturnedWhenKnownAndLeftUnsetWhenAbsent() {
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var positions = new InMemoryVehiclePositions(Duration.ofMinutes(10), 10);
        var source = new SourceId("krakow", "mpk-bus");
        positions.accept(progressing(source, "A", new StopProgress(source.scope("stop"), 0, VehicleStopStatus.INCOMING_AT), now), now);
        positions.accept(progressing(source, "B", new StopProgress(null, null, VehicleStopStatus.STOPPED_AT), now), now);
        positions.accept(progressing(source, "C", new StopProgress(null, 7, VehicleStopStatus.IN_TRANSIT_TO), now), now);
        positions.accept(progressing(source, "D", new StopProgress(null, null, VehicleStopStatus.UNRECOGNIZED), now), now);
        positions.accept(progressing(source, "E", StopProgress.NONE, now), now);
        var vehicles = list(new LiveVehiclesGrpcEndpoint(new LiveVehicleQueries(positions, noForecasts(), new MutableClock(now))));
        assertThat(vehicles.get(0)).satisfies(position -> {
            assertThat(position.getStopId()).isEqualTo("krakow:mpk-bus:stop");
            assertThat(position.hasCurrentStopSequence()).isTrue();
            assertThat(position.getCurrentStopSequence()).isZero();
            assertThat(position.getCurrentStatus())
                    .isEqualTo(pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus.VEHICLE_STOP_STATUS_INCOMING_AT);
        });
        assertThat(vehicles.get(1).getCurrentStatus())
                .isEqualTo(pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus.VEHICLE_STOP_STATUS_STOPPED_AT);
        assertThat(vehicles.get(2).getCurrentStatus())
                .isEqualTo(pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus.VEHICLE_STOP_STATUS_IN_TRANSIT_TO);
        assertThat(vehicles.get(3).getCurrentStatus())
                .isEqualTo(pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus.VEHICLE_STOP_STATUS_UNRECOGNIZED);
        assertThat(vehicles.get(4)).satisfies(position -> {
            assertThat(position.hasStopId()).isFalse();
            assertThat(position.hasCurrentStopSequence()).isFalse();
            assertThat(position.hasCurrentStatus()).isFalse();
        });
    }

    @Test
    void forecastRpcReturnsKnownStopsWithOptionalIdentityUntilTheForecastExpires() {
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var clock = new MutableClock(now);
        var forecasts = noForecasts();
        forecasts.accept(new TripForecast("krakow:mpk-bus:trip", now, List.of(
                new StopForecast(0, "krakow:mpk-bus:stop", now.plusSeconds(60)),
                new StopForecast(null, "krakow:mpk-bus:other", now.plusSeconds(120)),
                new StopForecast(7, null, now.plusSeconds(180)))), now);
        var endpoint = new LiveVehiclesGrpcEndpoint(new LiveVehicleQueries(new InMemoryVehiclePositions(Duration.ofMinutes(10), 10), forecasts, clock));
        var response = forecast(endpoint, "krakow:mpk-bus:trip");
        assertThat(response.getFetchedAt().getSeconds()).isEqualTo(now.getEpochSecond());
        assertThat(response.getStopsList()).hasSize(3);
        assertThat(response.getStops(0)).satisfies(stop -> {
            assertThat(stop.hasStopSequence()).isTrue();
            assertThat(stop.getStopSequence()).isZero();
            assertThat(stop.getStopId()).isEqualTo("krakow:mpk-bus:stop");
            assertThat(stop.getArrivalTime().getSeconds()).isEqualTo(now.plusSeconds(60).getEpochSecond());
        });
        assertThat(response.getStops(1).hasStopSequence()).isFalse();
        assertThat(response.getStops(2).hasStopId()).isFalse();
        clock.advance(Duration.ofMinutes(5));
        assertThat(forecast(endpoint, "krakow:mpk-bus:trip")).isEqualTo(GetTripForecastResponse.getDefaultInstance());
    }

    @Test
    void forecastRpcAnswersWithAnEmptyResponseForAnUnknownTrip() {
        var endpoint = new LiveVehiclesGrpcEndpoint(new LiveVehicleQueries(new InMemoryVehiclePositions(Duration.ofMinutes(10), 10),
                noForecasts(), new MutableClock(Instant.parse("2026-10-29T12:00:00Z"))));
        assertThat(forecast(endpoint, "krakow:mpk-bus:unknown")).isEqualTo(GetTripForecastResponse.getDefaultInstance());
    }

    private InMemoryTripForecasts noForecasts() { return new InMemoryTripForecasts(Duration.ofMinutes(5), 10); }

    private GetTripForecastResponse forecast(LiveVehiclesGrpcEndpoint endpoint, String tripId) {
        var responses = new ArrayList<GetTripForecastResponse>();
        endpoint.getTripForecast(GetTripForecastRequest.newBuilder().setTripId(tripId).build(), new StreamObserver<>() {
            @Override
            public void onNext(GetTripForecastResponse response) { responses.add(response); }

            @Override
            public void onError(Throwable failure) { throw new AssertionError(failure); }

            @Override
            public void onCompleted() {}
        });
        return responses.getFirst();
    }

    private VehiclePosition progressing(SourceId source, String plate, StopProgress progress, Instant now) {
        return new VehiclePosition(source, source.scope("trip"), null, plate, 50, 20, null, now, now,
                StreamId.parse("1-0"), progress);
    }

    private List<pl.krktransit.livevehicles.contracts.v1.VehiclePosition> list(LiveVehiclesGrpcEndpoint endpoint) {
        var responses = new ArrayList<ListVehiclePositionsResponse>();
        endpoint.listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance(), new StreamObserver<>() {
            @Override
            public void onNext(ListVehiclePositionsResponse response) { responses.add(response); }

            @Override
            public void onError(Throwable failure) { throw new AssertionError(failure); }

            @Override
            public void onCompleted() {}
        });
        return responses.getFirst().getVehiclesList();
    }
}
