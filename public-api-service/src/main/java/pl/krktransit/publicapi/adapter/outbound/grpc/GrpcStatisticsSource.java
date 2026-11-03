package pl.krktransit.publicapi.adapter.outbound.grpc;

import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.function.Function;
import pl.krktransit.publicapi.application.ApiFailure;
import pl.krktransit.publicapi.application.statistics.DelayMapStatistics;
import pl.krktransit.publicapi.application.statistics.MaxDelayRanking;
import pl.krktransit.publicapi.application.statistics.Punctuality;
import pl.krktransit.publicapi.application.statistics.RouteDelayRanking;
import pl.krktransit.publicapi.application.statistics.StatisticsQuery;
import pl.krktransit.publicapi.application.statistics.StatisticsSource;
import pl.krktransit.publicapi.application.statistics.Trend;
import pl.krktransit.stopevents.contracts.v1.StatisticsServiceGrpc;
import pl.krktransit.stopevents.contracts.v1.StatisticsServiceGrpc.StatisticsServiceBlockingStub;

public final class GrpcStatisticsSource implements StatisticsSource {
    private static final String SERVICE = "Statistics service";
    private final GrpcCalls<StatisticsServiceBlockingStub> calls;
    private final GrpcFailures failures = new GrpcFailures(SERVICE);
    private final StatisticsProtoMapper mapper = new StatisticsProtoMapper();

    public GrpcStatisticsSource(Channel channel, GrpcCallSettings settings) {
        this.calls = new GrpcCalls<>(StatisticsServiceGrpc.newBlockingStub(channel), settings);
    }

    @Override
    public DelayMapStatistics delayMap(StatisticsQuery query) {
        return mapper.delayMap(call(query, stub -> stub.getDelayMap(mapper.request(query))));
    }

    @Override
    public MaxDelayRanking maxDelay(StatisticsQuery query) {
        return mapper.maxDelay(call(query, stub -> stub.getMaxDelay(mapper.request(query))));
    }

    @Override
    public RouteDelayRanking routeDelay(StatisticsQuery query) {
        return mapper.routeDelay(call(query, stub -> stub.getRouteDelay(mapper.request(query))));
    }

    @Override
    public Punctuality punctuality(StatisticsQuery query) {
        return mapper.punctuality(call(query, stub -> stub.getPunctuality(mapper.request(query))));
    }

    @Override
    public Trend trend(StatisticsQuery query) {
        return mapper.trend(call(query, stub -> stub.getTrend(mapper.request(query))));
    }

    private <T> T call(StatisticsQuery query, Function<StatisticsServiceBlockingStub, T> rpc) {
        try {
            return rpc.apply(calls.next());
        } catch (StatusRuntimeException failure) {
            throw translate(failure, query);
        }
    }

    private ApiFailure translate(StatusRuntimeException failure, StatisticsQuery query) {
        if (GrpcFailures.isNotFound(failure)) return new ApiFailure.NotFound("Line", query.lineNumber());
        return failures.translate(failure);
    }
}
