package pl.krktransit.realtime.ingestion.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.Set;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class RealtimeRedisContainer extends GenericContainer<RealtimeRedisContainer> {
    private static final String OWNER_LABEL = "krktransit.test-owner";
    private static final int REDIS_PORT = 6379;
    private final String owner = UUID.randomUUID().toString();

    public RealtimeRedisContainer(String... serverArguments) {
        super("redis:7.4.7-alpine");
        withReuse(false);
        withLabel(OWNER_LABEL, owner);
        withExposedPorts(REDIS_PORT);
        withCommand(command(serverArguments));
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withMemory(512L * 1024 * 1024)
                .withNanoCPUs(1_000_000_000L)
                .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(REDIS_PORT))));
    }

    private static String[] command(String... serverArguments) {
        var command = new String[serverArguments.length + 5];
        command[0] = "redis-server";
        command[1] = "--save";
        command[2] = "";
        command[3] = "--appendonly";
        command[4] = "no";
        System.arraycopy(serverArguments, 0, command, 5, serverArguments.length);
        return command;
    }

    public String ownedUrl() {
        requireOwnedServer();
        return "redis://" + getHost() + ":" + getMappedPort(REDIS_PORT);
    }

    private void requireOwnedServer() {
        if (!isRunning() || !owner.equals(getLabels().get(OWNER_LABEL))) {
            throw new IllegalStateException("Integration requires this test's running Redis container");
        }
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(getHost())) {
            throw new IllegalStateException("Realtime integration requires a local Docker host and loopback ports");
        }
    }

    public void pause() {
        getDockerClient().pauseContainerCmd(getContainerId()).exec();
    }

    public void unpause() {
        getDockerClient().unpauseContainerCmd(getContainerId()).exec();
    }
}
