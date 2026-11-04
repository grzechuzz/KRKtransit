package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.krktransit.livevehicles.contracts.v1.GetTripForecastRequest;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc.LiveVehiclesServiceBlockingStub;
import pl.krktransit.publicapi.application.upcoming.OperatorForecast;
import pl.krktransit.publicapi.application.upcoming.OperatorForecastSource;

public final class GrpcOperatorForecastSource implements OperatorForecastSource {
    private static final System.Logger LOG = System.getLogger(GrpcOperatorForecastSource.class.getName());
    private final GrpcCalls<LiveVehiclesServiceBlockingStub> calls;
    private final OperatorForecastProtoMapper mapper = new OperatorForecastProtoMapper();
    private final AtomicBoolean failing = new AtomicBoolean();

    public GrpcOperatorForecastSource(Channel channel, GrpcCallSettings settings) {
        this.calls = new GrpcCalls<>(LiveVehiclesServiceGrpc.newBlockingStub(channel), settings);
    }

    @Override
    public OperatorForecast forecast(String tripId) {
        try {
            var forecast = mapper.forecast(calls.next().getTripForecast(GetTripForecastRequest.newBuilder().setTripId(tripId).build()));
            recovered();
            return forecast;
        } catch (StatusRuntimeException failure) {
            failed(failure);
            return OperatorForecast.NONE;
        }
    }

    private void recovered() {
        if (failing.compareAndSet(true, false)) LOG.log(System.Logger.Level.INFO, "Trip forecasts are available again");
    }

    private void failed(StatusRuntimeException failure) {
        if (failing.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "Trip forecast call failed with {0}; serving scheduled times only",
                    failure.getStatus().getCode());
        }
    }
}
