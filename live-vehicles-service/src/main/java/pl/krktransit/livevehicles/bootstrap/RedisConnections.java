package pl.krktransit.livevehicles.bootstrap;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

public final class RedisConnections implements AutoCloseable {
    private final RedisClient client;
    private final RedisURI uri;

    public RedisConnections(LiveVehiclesProperties.Redis settings) {
        uri = redisUri(settings);
        client = RedisClient.create(uri);
        client.setOptions(options(settings));
    }

    private RedisURI redisUri(LiveVehiclesProperties.Redis settings) {
        var builder = RedisURI.builder(RedisURI.create(settings.url())).withClientName(settings.clientName())
                .withTimeout(settings.commandTimeout());
        applyAuthentication(builder, settings);
        return builder.build();
    }

    private void applyAuthentication(RedisURI.Builder builder, LiveVehiclesProperties.Redis settings) {
        if (!hasPassword(settings)) return;
        builder.withAuthentication(settings.username(), settings.password().toCharArray());
    }

    private boolean hasPassword(LiveVehiclesProperties.Redis settings) {
        return settings.password() != null && !settings.password().isEmpty();
    }

    private ClientOptions options(LiveVehiclesProperties.Redis settings) {
        return ClientOptions.builder().autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                .timeoutOptions(TimeoutOptions.builder().fixedTimeout(settings.commandTimeout()).build()).build();
    }

    public StatefulRedisConnection<String, byte[]> open() {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri);
    }

    @Override
    public void close() { client.shutdown(); }
}
