package pl.krktransit.livevehicles.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.Set;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class OwnedRedis extends GenericContainer<OwnedRedis> {
    private final String owner = UUID.randomUUID().toString();

    public OwnedRedis() {
        super("redis:7.4.7-alpine");
        withReuse(false);
        withLabel("krktransit.test-owner", owner);
        withExposedPorts(6379);
        withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory-policy", "noeviction");
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(256L * 1024 * 1024)
                .withNanoCPUs(1_000_000_000L).withPortBindings(
                        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(6379))));
    }

    public String ownedUrl() {
        if (!isRunning() || !owner.equals(getLabels().get("krktransit.test-owner"))) {
            throw new IllegalStateException("Test requires its owned Redis container");
        }
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(getHost())) {
            throw new IllegalStateException("Tests require local loopback Docker ports");
        }
        return "redis://" + getHost() + ":" + getMappedPort(6379);
    }
}
