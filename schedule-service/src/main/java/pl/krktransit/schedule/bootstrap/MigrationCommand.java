package pl.krktransit.schedule.bootstrap;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

public final class MigrationCommand {
    private MigrationCommand() {}

    public static void run(Map<String, String> environment) {
        configuration()
                .dataSource(required(environment, "SCHEDULE_DB_URL"), required(environment, "SCHEDULE_DB_USER"),
                        required(environment, "SCHEDULE_DB_PASSWORD"))
                .load().migrate();
    }

    public static void migrate(DataSource source) {
        configuration().dataSource(source).load().migrate();
    }

    private static FluentConfiguration configuration() {
        return Flyway.configure()
                .defaultSchema("gtfs_schedule")
                .schemas("gtfs_schedule")
                .locations("classpath:db/migration")
                .createSchemas(true)
                .cleanDisabled(true)
                .baselineOnMigrate(false);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required for --migrate");
        return value;
    }
}
