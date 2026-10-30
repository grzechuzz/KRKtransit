package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.List;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc.LiveVehiclesServiceBlockingStub;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;
import pl.krktransit.publicapi.application.vehicles.VehiclePositionsSource;

public final class GrpcVehiclePositionsSource implements VehiclePositionsSource {
    private static final String SERVICE = "Live vehicles service";
    private final GrpcCalls<LiveVehiclesServiceBlockingStub> calls;
    private final GrpcFailures failures = new GrpcFailures(SERVICE);
    private final VehicleProtoMapper mapper = new VehicleProtoMapper();

    public GrpcVehiclePositionsSource(Channel channel, GrpcCallSettings settings) {
        this.calls = new GrpcCalls<>(LiveVehiclesServiceGrpc.newBlockingStub(channel), settings);
    }

    @Override
    public List<VehiclePosition> positions() {
        try {
            return mapper.positions(calls.next().listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance()));
        } catch (StatusRuntimeException failure) {
            throw failures.translate(failure);
        }
    }
}
