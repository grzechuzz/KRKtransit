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
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import pl.krktransit.livevehicles.adapter.outbound.memory.InMemoryVehiclePositions;
import pl.krktransit.livevehicles.application.LiveVehicleQueries;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.StreamId;
import pl.krktransit.livevehicles.domain.VehiclePosition;
import pl.krktransit.livevehicles.support.MutableClock;

class LiveVehiclesGrpcEndpointTest {
    @Test
    void authenticatedRpcReturnsEveryRetainedPositionAndPreservesOptionalValues() throws Exception {
        var now = Instant.parse("2026-10-29T12:00:00Z");
        var clock = new MutableClock(now);
        var positions = new InMemoryVehiclePositions(Duration.ofMinutes(10), 10);
        var source = new SourceId("krakow", "mpk-bus");
        positions.accept(new VehiclePosition(source, source.scope("trip"), null, "A", 0, 0, null,
                now.minusSeconds(300), now, StreamId.parse("1-0")), now);
        positions.accept(new VehiclePosition(source, null, "vehicle", null, 50, 20, 0.0, null, now, StreamId.parse("2-0")), now);
        var queries = new LiveVehicleQueries(positions, clock);
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
}
