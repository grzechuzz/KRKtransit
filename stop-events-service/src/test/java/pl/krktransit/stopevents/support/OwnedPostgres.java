package pl.krktransit.stopevents.support;

import com.github.dockerjava.api.model.*;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public final class OwnedPostgres extends PostgreSQLContainer {
    private final String owner = UUID.randomUUID().toString();

    public OwnedPostgres() {
        super("postgres:17");
        withDatabaseName("stop_events_test");
        withUsername("test_owner");
        withPassword("disposable-test-only");
        withReuse(false);
        withLabel("krktransit.test-owner", owner);
        withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(1024L * 1024 * 1024)
                .withNanoCPUs(2_000_000_000L)
                .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5432))));
    }

    public DataSource dataSource() {
        requireOwnership();
        var source = new PGSimpleDataSource();
        source.setURL(getJdbcUrl());
        source.setUser(getUsername());
        source.setPassword(getPassword());
        return source;
    }

    public void requireOwnership() {
        if (!isRunning() || !owner.equals(getLabels().get("krktransit.test-owner"))) {
            throw new IllegalStateException("Test requires its owned PostgreSQL container");
        }
        if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(getHost())) {
            throw new IllegalStateException("Tests require local loopback Docker ports");
        }
    }
}
