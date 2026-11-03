package pl.krktransit.stopevents.adapter.inbound.grpc;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import pl.krktransit.stopevents.application.statistics.QueryCancellation;
import pl.krktransit.stopevents.application.statistics.StatisticsFailure;
import pl.krktransit.stopevents.application.statistics.StatisticsQueries;
import pl.krktransit.stopevents.contracts.v1.*;
import pl.krktransit.stopevents.domain.statistics.InvalidStatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

public final class StatisticsGrpcEndpoint extends StatisticsServiceGrpc.StatisticsServiceImplBase {
    private static final System.Logger LOG = System.getLogger(StatisticsGrpcEndpoint.class.getName());
    private final StatisticsQueries queries;
    private final StatisticsResponseMapper mapper = new StatisticsResponseMapper();

    public StatisticsGrpcEndpoint(StatisticsQueries queries) { this.queries = queries; }

    @Override
    public void getMaxDelay(StatisticsRequest request, StreamObserver<MaxDelayResponse> observer) {
        respond(request, Statistic.MAX_DELAY, observer, result -> mapper.maxDelay(request, (StatisticsResult.MaxDelay) result));
    }

    @Override
    public void getRouteDelay(StatisticsRequest request, StreamObserver<RouteDelayResponse> observer) {
        respond(request, Statistic.ROUTE_DELAY, observer, result -> mapper.routeDelay(request, (StatisticsResult.RouteDelay) result));
    }

    @Override
    public void getPunctuality(StatisticsRequest request, StreamObserver<PunctualityResponse> observer) {
        respond(request, Statistic.PUNCTUALITY, observer, result -> mapper.punctuality(request, (StatisticsResult.Punctuality) result));
    }

    @Override
    public void getTrend(StatisticsRequest request, StreamObserver<TrendResponse> observer) {
        respond(request, Statistic.TREND, observer, result -> mapper.trend(request, (StatisticsResult.Trend) result));
    }

    private <T> void respond(StatisticsRequest request, Statistic statistic, StreamObserver<T> observer,
                             Function<StatisticsResult, T> response) {
        var context = Context.current();
        var cancellation = new QueryCancellation();
        Context.CancellationListener listener = ignored -> cancellation.cancel();
        context.addListener(listener, Runnable::run);
        try { send(request, statistic, observer, response, cancellation); }
        finally { context.removeListener(listener); }
    }

    private <T> void send(StatisticsRequest request, Statistic statistic, StreamObserver<T> observer,
                          Function<StatisticsResult, T> response, QueryCancellation cancellation) {
        try {
            var result = queries.get(statistic, query(request), cancellation);
            cancellation.check();
            observer.onNext(response.apply(result));
            observer.onCompleted();
        } catch (InvalidStatisticsQuery failure) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(failure.getMessage()).asRuntimeException());
        } catch (StatisticsFailure failure) {
            observer.onError(status(failure).withDescription(failure.getMessage()).asRuntimeException());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Statistics query failed", failure);
            observer.onError(Status.INTERNAL.withDescription("Statistics query failed").asRuntimeException());
        }
    }

    private StatisticsQuery query(StatisticsRequest request) {
        try {
            return new StatisticsQuery(request.getLineNumber(), LocalDate.parse(request.getStartDate()),
                    LocalDate.parse(request.getEndDate()), request.getIncludeEstimated());
        } catch (DateTimeParseException failure) { throw new InvalidStatisticsQuery("Dates must use YYYY-MM-DD", failure); }
    }

    private Status status(StatisticsFailure failure) {
        return switch (failure.kind()) {
            case NOT_FOUND -> Status.NOT_FOUND;
            case UNAVAILABLE -> Status.UNAVAILABLE;
            case BUSY -> Status.RESOURCE_EXHAUSTED;
            case CANCELLED -> Status.CANCELLED;
        };
    }
}
