package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.time.Duration;

public final class RedisClients implements AutoCloseable {
    private static final Duration SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private final RedisURI uri;
    private final ClientOptions options;
    private final RedisClient client;

    public RedisClients(String url, String username, String password, String clientName, Duration connectTimeout,
                        Duration commandTimeout) {
        validateConnectionSettings(url, connectTimeout, commandTimeout);
        this.uri = uri(url, username, password, clientName, commandTimeout);
        this.options = options(connectTimeout, commandTimeout);
        this.client = RedisClient.create(uri);
        client.setOptions(options);
    }

    private static void validateConnectionSettings(String url, Duration connectTimeout, Duration commandTimeout) {
        if (!hasUrl(url)) {
            throw new IllegalArgumentException("Redis URL is required");
        }
        if (!hasValidTimeouts(connectTimeout, commandTimeout)) {
            throw new IllegalArgumentException("Redis timeouts must be positive");
        }
    }

    private static boolean hasUrl(String url) {
        return url != null && !url.isBlank();
    }

    private static boolean hasValidTimeouts(Duration connectTimeout, Duration commandTimeout) {
        return connectTimeout.isPositive() && commandTimeout.isPositive();
    }

    private static RedisURI uri(String url, String username, String password, String clientName,
                                Duration commandTimeout) {
        var builder = RedisURI.builder(RedisURI.create(url)).withClientName(clientName).withTimeout(commandTimeout);
        if (hasPassword(password)) {
            builder.withAuthentication(username, password.toCharArray());
        }
        return builder.build();
    }

    private static boolean hasPassword(String password) {
        return password != null && !password.isEmpty();
    }

    private static ClientOptions options(Duration connectTimeout, Duration commandTimeout) {
        return ClientOptions.builder()
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .timeoutOptions(TimeoutOptions.builder().fixedTimeout(commandTimeout).build())
                .build();
    }

    ClientOptions options() {
        return options;
    }

    StreamWriter connect() {
        StatefulRedisConnection<byte[], byte[]> connection;
        try {
            connection = client.connect(ByteArrayCodec.INSTANCE, uri);
        } catch (RedisException failure) {
            throw unavailable(failure);
        }
        return verified(connection);
    }

    private StreamWriter verified(StatefulRedisConnection<byte[], byte[]> connection) {
        try {
            connection.sync().ping();
            return new LettuceStreamWriter(connection);
        } catch (RedisException failure) {
            connection.close();
            throw unavailable(failure);
        }
    }

    private RedisUnavailable unavailable(RedisException failure) {
        return new RedisUnavailable("Redis at " + uri.getHost() + ":" + uri.getPort() + " is unavailable", failure);
    }

    @Override
    public void close() {
        client.shutdown(SHUTDOWN_QUIET_PERIOD, uri.getTimeout());
    }
}
