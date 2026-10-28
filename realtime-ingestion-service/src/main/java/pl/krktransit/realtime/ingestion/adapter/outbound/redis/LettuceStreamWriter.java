package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import io.lettuce.core.RedisException;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class LettuceStreamWriter implements StreamWriter {
    private static final byte[] PAYLOAD_FIELD = "payload".getBytes(StandardCharsets.UTF_8);
    private final StatefulRedisConnection<byte[], byte[]> connection;

    LettuceStreamWriter(StatefulRedisConnection<byte[], byte[]> connection) {
        this.connection = connection;
    }

    @Override
    public boolean isOpen() {
        return connection.isOpen();
    }

    @Override
    public CompletionStage<String> append(StreamTarget target, byte[] payload) {
        var trimming = XAddArgs.Builder.maxlen(target.maxLength()).approximateTrimming();
        try {
            return connection.async().xadd(target.key(), trimming, PAYLOAD_FIELD, payload);
        } catch (RedisException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
