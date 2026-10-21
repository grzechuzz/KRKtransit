package pl.krktransit.schedule.support;

import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class SchedulePostgresContainer extends PostgreSQLContainer {
    private static final String OWNER_LABEL = "krktransit.test-owner";
    private final String owner = UUID.randomUUID().toString();
    private final Network network = Network.builder().createNetworkCmdModifier(this::labelNetwork).build();

    public SchedulePostgresContainer() {
        super("postgres:17-alpine");
        withDatabaseName("krk_schedule_test");
        withUsername("schedule_test");
        withPassword("disposable-test-only");
        withReuse(false);
        withLabel(OWNER_LABEL, owner);
        withNetwork(network);
        withTmpFs(Map.of("/var/lib/postgresql/data", "rw,size=512m"));
        withCreateContainerCmdModifier(command -> command.getHostConfig()
                .withMemory(768L * 1024 * 1024)
                .withNanoCPUs(2_000_000_000L)
                .withShmSize(64L * 1024 * 1024)
                .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    }

    public DataSource dataSource() {
        requireOwnedDatabase();
        var source = new PGSimpleDataSource();
        source.setURL(getJdbcUrl());
        source.setUser(getUsername());
        source.setPassword(getPassword());
        return source;
    }

    private void requireOwnedDatabase() {
        if (!isRunning() || !owner.equals(getLabels().get(OWNER_LABEL))) {
            throw new IllegalStateException("Integration requires this test's running PostgreSQL container");
        }
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(getHost())) {
            throw new IllegalStateException("Schedule integration requires a local Docker host and loopback ports");
        }
    }

    private void labelNetwork(CreateNetworkCmd command) {
        var labels = new HashMap<String, String>();
        if (command.getLabels() != null) {
            labels.putAll(command.getLabels());
        }
        labels.put(OWNER_LABEL, owner);
        command.withLabels(labels);
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            network.close();
        }
    }
}
