package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import javax.sql.DataSource;
import pl.krktransit.schedule.application.importing.PublicationOutcome;
import pl.krktransit.schedule.application.port.SchedulePublication;
import pl.krktransit.schedule.application.port.ScheduleWriter;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class PostgresSchedulePublication implements SchedulePublication {
    private final DataSource source;
    private final Clock clock;

    public PostgresSchedulePublication(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public void registerFeeds(Collection<FeedId> feeds) {
        try (var transaction = PostgresTransaction.write(source)) {
            ScheduleWriteLock.acquire(transaction.connection());
            for (FeedId feed : feeds) {
                PostgresVersions.register(transaction.connection(), feed);
            }
            transaction.commit();
        } catch (SQLException failure) {
            throw PostgresFailures.translate("register sources", failure);
        }
    }

    @Override
    public Optional<ScheduleHash> currentHash(FeedId feed) {
        try (var transaction = PostgresTransaction.read(source)) {
            return PostgresVersions.currentHash(transaction.connection(), feed);
        } catch (SQLException failure) {
            throw PostgresFailures.translate("read current hash", failure);
        }
    }

    @Override
    public PublicationOutcome publish(FeedId feed, ScheduleHash hash, Consumer<ScheduleWriter> load) {
        PostgresFailures.requireNotInterrupted();
        try (var transaction = PostgresTransaction.write(source)) {
            ScheduleWriteLock.acquire(transaction.connection());
            PostgresVersions.register(transaction.connection(), feed);
            var registered = PostgresVersions.feed(transaction.connection(), feed);
            var reusable = PostgresVersions.reusable(transaction.connection(), registered.feedKey(), hash);
            if (isCurrentVersion(reusable, registered.currentVersionKey())) {
                transaction.commit();
                return PublicationOutcome.UNCHANGED;
            }
            long versionKey = reusable.isPresent() ? reusable.getAsLong()
                    : importNewVersion(transaction, registered.feedKey(), hash, load);
            PostgresFailures.requireNotInterrupted();
            PostgresVersions.activate(transaction.connection(), registered, versionKey, clock.instant());
            transaction.commit();
            return reusable.isPresent() ? PublicationOutcome.REACTIVATED : PublicationOutcome.PUBLISHED;
        } catch (SQLException failure) {
            throw PostgresFailures.translate("publish schedule", failure);
        }
    }

    private static boolean isCurrentVersion(OptionalLong reusable, Long currentVersionKey) {
        return reusable.isPresent() && Long.valueOf(reusable.getAsLong()).equals(currentVersionKey);
    }

    private long importNewVersion(PostgresTransaction transaction, long feedKey, ScheduleHash hash,
                                  Consumer<ScheduleWriter> load) throws SQLException {
        long versionKey = PostgresVersions.create(transaction.connection(), feedKey, hash, clock.instant());
        var writer = new PostgresScheduleWriter(transaction.connection(), versionKey);
        load.accept(writer);
        writer.requireComplete();
        return versionKey;
    }

    @Override
    public long purgeExpired(int batchSize) {
        return new PostgresVersionPurger(source, clock).purge(batchSize);
    }
}
