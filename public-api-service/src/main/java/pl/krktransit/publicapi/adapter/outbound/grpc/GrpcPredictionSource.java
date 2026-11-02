package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.atomic.AtomicBoolean;
import pl.krktransit.prediction.contracts.v1.PredictionServiceGrpc;
import pl.krktransit.prediction.contracts.v1.PredictionServiceGrpc.PredictionServiceBlockingStub;
import pl.krktransit.publicapi.application.upcoming.PredictedDelays;
import pl.krktransit.publicapi.application.upcoming.PredictionRequest;
import pl.krktransit.publicapi.application.upcoming.PredictionSource;

public final class GrpcPredictionSource implements PredictionSource {
    private static final System.Logger LOG = System.getLogger(GrpcPredictionSource.class.getName());
    private final GrpcCalls<PredictionServiceBlockingStub> calls;
    private final PredictionProtoMapper mapper = new PredictionProtoMapper();
    private final AtomicBoolean failing = new AtomicBoolean();

    public GrpcPredictionSource(Channel channel, GrpcCallSettings settings) {
        this.calls = new GrpcCalls<>(PredictionServiceGrpc.newBlockingStub(channel), settings);
    }

    @Override
    public PredictedDelays predict(PredictionRequest request) {
        try {
            var delays = mapper.delays(calls.next().predictArrivals(mapper.request(request)));
            recovered();
            return delays;
        } catch (StatusRuntimeException failure) {
            failed(failure);
            return PredictedDelays.NONE;
        }
    }

    private void recovered() {
        if (failing.compareAndSet(true, false)) LOG.log(System.Logger.Level.INFO, "Prediction service answers again");
    }

    private void failed(StatusRuntimeException failure) {
        if (failing.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "Prediction service call failed with {0}; serving scheduled times only",
                    failure.getStatus().getCode());
        }
    }
}
