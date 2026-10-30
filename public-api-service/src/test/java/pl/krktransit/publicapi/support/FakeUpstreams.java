package pl.krktransit.publicapi.support;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FakeUpstreams implements AutoCloseable {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    public final FakeStatisticsService statistics = new FakeStatisticsService();
    public final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final String name;
    private final Server server;

    private FakeUpstreams(String name) throws IOException {
        this.name = name;
        ServerInterceptor recordAuthorization = this::recordAuthorization;
        this.server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(statistics, recordAuthorization)).build().start();
    }

    public static FakeUpstreams start(String name) throws IOException { return new FakeUpstreams(name); }

    public String address() { return "in-process:" + name; }

    public ManagedChannel channel() { return InProcessChannelBuilder.forName(name).build(); }

    public void reset() {
        statistics.reset();
        authorizations.clear();
    }

    @Override
    public void close() { server.shutdownNow(); }

    private <Q, R> ServerCall.Listener<Q> recordAuthorization(ServerCall<Q, R> call, Metadata headers,
                                                             ServerCallHandler<Q, R> next) {
        var authorization = headers.get(AUTHORIZATION);
        authorizations.add(authorization == null ? "" : authorization);
        return next.startCall(call, headers);
    }
}
