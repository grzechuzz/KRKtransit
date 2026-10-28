package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RedisClientsTest {
    @Test
    void neitherReplaysNorQueuesCommandsWhileDisconnectedAndTimesOutEveryCommand() {
        try (var clients = clients("redis://127.0.0.1:6379")) {
            var options = clients.options();

            assertThat(options.isAutoReconnect()).isFalse();
            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
            assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Test
    void unreachableRedisIsReportedAsUnavailable() throws IOException {
        try (var clients = clients("redis://127.0.0.1:" + closedLoopbackPort())) {
            assertThatThrownBy(clients::connect).isInstanceOf(RedisUnavailable.class)
                    .hasMessageContaining("127.0.0.1");
        }
    }

    @Test
    void rejectsMissingUrlAndNonPositiveTimeouts() {
        assertThatThrownBy(() -> new RedisClients(" ", null, null, "test", Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisClients("redis://127.0.0.1", null, null, "test", Duration.ZERO,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static RedisClients clients(String url) {
        return new RedisClients(url, null, null, "realtime-test", Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private static int closedLoopbackPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
