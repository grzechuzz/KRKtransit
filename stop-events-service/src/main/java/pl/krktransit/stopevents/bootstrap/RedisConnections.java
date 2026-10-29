package pl.krktransit.stopevents.bootstrap;

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

    public RedisConnections(StopEventsProperties.Redis settings) {
        var builder = RedisURI.builder(RedisURI.create(settings.url())).withClientName(settings.clientName())
                .withTimeout(settings.commandTimeout());
        if (settings.password() != null && !settings.password().isEmpty()) {
            builder.withAuthentication(settings.username(), settings.password().toCharArray());
        }
        uri = builder.build();
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                .timeoutOptions(TimeoutOptions.builder().fixedTimeout(settings.commandTimeout()).build()).build());
    }

    public StatefulRedisConnection<String, byte[]> open() {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri);
    }

    @Override
    public void close() { client.shutdown(); }
}
