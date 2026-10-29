package pl.krktransit.stopevents.adapter.outbound.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import pl.krktransit.stopevents.application.port.DetectorStateStore;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.application.processing.StateChanges;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.state.SavedStop;
import pl.krktransit.stopevents.domain.state.TripForecasts;
import pl.krktransit.stopevents.domain.state.TripKey;
import pl.krktransit.stopevents.domain.state.TripPin;
import pl.krktransit.stopevents.domain.state.VehicleKey;
import pl.krktransit.stopevents.domain.state.VehicleState;

public final class RedisDetectorStateStore implements DetectorStateStore, AutoCloseable {
    private static final long SAFETY_TTL_SECONDS = Duration.ofHours(24).toSeconds();
    private final Supplier<StatefulRedisConnection<String, byte[]>> connections;
    private final StateKeys keys;
    private final StateCodec codec = new StateCodec();
    private final Duration inactivity;
    private StatefulRedisConnection<String, byte[]> connection;

    public RedisDetectorStateStore(Supplier<StatefulRedisConnection<String, byte[]>> connections,
                                    String prefix, Duration inactivity) {
        this.connections = connections;
        this.keys = new StateKeys(prefix);
        this.inactivity = inactivity;
    }

    @Override
    public void checkAvailable() {
        read(commands -> {
            commands.ping();
            if (commands.get(keys.fault()) != null) {
                throw new StateIntegrityFailure("Detector state is quarantined; manual repair is required");
            }
            return null;
        });
    }

    @Override
    public VehicleState vehicle(VehicleKey key) { return read(commands -> codec.vehicle(commands.get(keys.vehicle(key)))); }
    @Override
    public TripPin pin(TripKey key) { return read(commands -> codec.pin(commands.get(keys.pin(key)))); }
    @Override
    public TripForecasts forecasts(TripKey key) { return read(commands -> codec.forecasts(commands.get(keys.forecasts(key)))); }

    @Override
    public Map<EventKey, SavedStop> savedStops(TripKey key, LocalDate date) {
        return read(commands -> {
            var saved = new HashMap<EventKey, SavedStop>();
            commands.hgetall(keys.saved(key, date)).forEach((sequence, bytes) ->
                    saved.put(new EventKey(key.tripId(), date, sequence(sequence)), codec.saved(bytes)));
            return Map.copyOf(saved);
        });
    }

    private int sequence(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new StateIntegrityFailure("Malformed saved-stop sequence", failure); }
    }

    @Override
    public Map<SourceId, String> cursors(Set<SourceId> sources) {
        return read(commands -> {
            var cursors = new HashMap<SourceId, String>();
            for (var source : sources) {
                var bytes = commands.get(keys.cursor(source));
                if (bytes != null) cursors.put(source, new String(bytes, StandardCharsets.UTF_8));
            }
            return Map.copyOf(cursors);
        });
    }

    @Override
    public Map<TripKey, TripPin> pin(Map<TripKey, TripPin> requested) {
        if (requested.isEmpty()) return Map.of();
        return read(commands -> {
            var stored = new HashMap<TripKey, TripPin>();
            requested.forEach((key, value) -> stored.put(key, pin(commands, key, value)));
            return Map.copyOf(stored);
        });
    }

    private TripPin pin(RedisCommands<String, byte[]> commands, TripKey key, TripPin selected) {
        String redisKey = keys.pin(key);
        var previous = codec.pin(commands.get(redisKey));
        if (previous != null && previous.expired(selected.fetchedAt(), inactivity)) commands.del(redisKey);
        commands.set(redisKey, codec.pin(selected), SetArgs.Builder.nx().ex(SAFETY_TTL_SECONDS));
        var stored = codec.pin(commands.get(redisKey));
        if (stored == null) throw new StorageFailure(StorageFailure.Kind.REDIS, "Trip pin disappeared", null);
        return stored;
    }

    @Override
    public void commit(StateChanges changes) {
        RedisCommands<String, byte[]> commands;
        try {
            commands = connection().sync();
            commands.multi();
            queue(commands, changes);
        } catch (RedisException failure) {
            reset();
            throw unavailable(failure, StorageFailure.Kind.REDIS);
        }
        execute(commands);
    }

    private void queue(RedisCommands<String, byte[]> commands, StateChanges changes) {
        changes.deletedVehicles().forEach(key -> commands.del(keys.vehicle(key)));
        changes.deletedForecasts().forEach(key -> commands.del(keys.forecasts(key)));
        changes.vehicles().forEach((key, value) -> put(commands, keys.vehicle(key), codec.vehicle(value)));
        changes.forecasts().forEach((key, value) -> put(commands, keys.forecasts(key), codec.forecasts(value)));
        changes.pins().forEach((key, value) -> put(commands, keys.pin(key), codec.pin(value)));
        changes.savedStops().forEach((key, value) -> saveStop(commands, key, value, changes.tripSources()));
        changes.cursors().forEach((source, cursor) -> commands.set(keys.cursor(source), cursor.getBytes(StandardCharsets.UTF_8)));
    }

    private void put(RedisCommands<String, byte[]> commands, String key, byte[] value) {
        commands.set(key, value, SetArgs.Builder.ex(SAFETY_TTL_SECONDS));
    }

    private void saveStop(RedisCommands<String, byte[]> commands, EventKey key, SavedStop value,
                           Map<String, SourceId> sources) {
        String redisKey = keys.saved(new TripKey(sources.get(key.tripId()), key.tripId()), key.serviceDate());
        commands.hset(redisKey, Integer.toString(key.sequence()), codec.saved(value));
        commands.expire(redisKey, SAFETY_TTL_SECONDS);
    }

    private void execute(RedisCommands<String, byte[]> commands) {
        TransactionResult result;
        try {
            result = commands.exec();
        } catch (RedisCommandExecutionException failure) {
            reset();
            if (failure.getMessage().startsWith("EXECABORT")) throw unavailable(failure, StorageFailure.Kind.REDIS);
            throw new StateIntegrityFailure("Redis commit failed; manual repair is required", failure);
        } catch (RedisException failure) {
            reset();
            throw unavailable(failure, StorageFailure.Kind.UNCERTAIN_COMMIT);
        }
        verify(result, commands);
    }

    private void verify(TransactionResult result, RedisCommands<String, byte[]> commands) {
        if (result.wasDiscarded()) throw new StateIntegrityFailure("Unexpected discarded detector transaction");
        if (result.stream().noneMatch(value -> value instanceof Throwable)) return;
        commands.set(keys.fault(), "partial-commit".getBytes(StandardCharsets.UTF_8));
        throw new StateIntegrityFailure("Partial Redis commit; detector quarantined for manual repair");
    }

    private <T> T read(Function<RedisCommands<String, byte[]>, T> operation) {
        try {
            return operation.apply(connection().sync());
        } catch (RedisCommandExecutionException failure) {
            reset();
            if (failure.getMessage().startsWith("WRONGTYPE")) {
                throw new StateIntegrityFailure("Unexpected Redis state type; manual repair is required", failure);
            }
            throw unavailable(failure, StorageFailure.Kind.REDIS);
        } catch (RedisException failure) {
            reset();
            throw unavailable(failure, StorageFailure.Kind.REDIS);
        }
    }

    private StatefulRedisConnection<String, byte[]> connection() {
        if (connection == null) connection = connections.get();
        return connection;
    }

    private StorageFailure unavailable(RedisException failure, StorageFailure.Kind kind) {
        return new StorageFailure(kind, "Detector Redis is unavailable", failure);
    }

    private void reset() {
        if (connection != null) connection.close();
        connection = null;
    }

    @Override
    public void close() { reset(); }
}
