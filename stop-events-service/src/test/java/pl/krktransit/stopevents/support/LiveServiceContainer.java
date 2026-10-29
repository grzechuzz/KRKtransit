package pl.krktransit.stopevents.support;

import com.github.dockerjava.api.model.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public final class LiveServiceContainer extends GenericContainer<LiveServiceContainer> {
    public LiveServiceContainer(String module, Network network, String owner, Map<String, String> environment) {
        super("eclipse-temurin:25-jre");
        withReuse(false);
        withLabel("krktransit.test-owner", owner);
        withNetwork(network);
        withNetworkAliases(module);
        withEnv(environment);
        withCopyFileToContainer(MountableFile.forHostPath(artifact(module)), "/app/service.jar");
        withCommand("java", "-Xmx384m", "-jar", "/app/service.jar");
        withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withMemory(768L * 1024 * 1024).withNanoCPUs(2_000_000_000L));
        withStartupTimeout(Duration.ofMinutes(2));
    }

    public LiveServiceContainer readyLog(String expression) {
        waitingFor(Wait.forLogMessage(expression, 1).withStartupTimeout(Duration.ofMinutes(2)));
        return this;
    }

    public LiveServiceContainer migration() {
        withCommand("java", "-Xmx256m", "-jar", "/app/service.jar", "--migrate");
        withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
        return readyLog(".*Successfully applied.*\n");
    }

    public LiveServiceContainer grpcPort() {
        withExposedPorts(9090);
        withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(9090))));
        return this;
    }

    private static Path artifact(String module) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("schedule-service"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate the reactor root");
        var jar = root.resolve(module + "/target/" + module + "-0.1.0.jar");
        if (!java.nio.file.Files.isRegularFile(jar)) throw new IllegalStateException("Build all services before the live test: " + jar);
        return jar;
    }
}
