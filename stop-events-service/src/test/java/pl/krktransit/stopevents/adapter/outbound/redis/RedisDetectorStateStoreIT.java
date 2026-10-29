package pl.krktransit.stopevents.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.stopevents.support.DetectorFixtures.*;

import io.lettuce.core.RedisClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import pl.krktransit.stopevents.application.port.*;
import pl.krktransit.stopevents.application.processing.StateChanges;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.state.*;
import pl.krktransit.stopevents.support.OwnedRedis;

@Testcontainers
class RedisDetectorStateStoreIT {
    @Container
    private static final OwnedRedis REDIS = new OwnedRedis();
    private RedisClient client;
    private RedisDetectorStateStore store;
    private String prefix;
    private final TripKey key = new TripKey(SOURCE, TRIP);

    @BeforeEach
    void prepare() {
        client = RedisClient.create(REDIS.ownedUrl());
        prefix = "krktransit:stop-events:test:" + UUID.randomUUID();
        store = new RedisDetectorStateStore(() -> REDIS.connect(client), prefix, Duration.ofHours(3));
    }

    @AfterEach
    void close() { store.close(); client.shutdown(); }

    @Test
    void roundTripsStateAndCursorInOneCommit() {
        store.checkAvailable();
        store.pin(Map.of(key, new TripPin(HASH, BASE)));
        store.commit(changes("1-0"));
        assertThat(store.vehicle(new VehicleKey(SOURCE, "KR123"))).isEqualTo(previous(2));
        assertThat(store.forecasts(key)).isEqualTo(forecasts());
        assertThat(store.cursors(Set.of(SOURCE))).containsEntry(SOURCE, "1-0");
        assertThat(store.savedStops(key, DATE)).containsEntry(new EventKey(TRIP, DATE, 2), new SavedStop(10, BASE));
    }

    @Test
    void pinKeepsTheFirstHashUntilLogicalInactivityExpires() {
        store.pin(Map.of(key, new TripPin(HASH, BASE)));
        var newer = new TripPin("b".repeat(64), BASE.plusSeconds(1));
        assertThat(store.pin(Map.of(key, newer)).get(key).hash()).isEqualTo(HASH);
        var nextRun = new TripPin("b".repeat(64), BASE.plusSeconds(10801));
        assertThat(store.pin(Map.of(key, nextRun)).get(key).hash()).isEqualTo(nextRun.hash());
    }

    @Test
    void partialExecQuarantinesStateEvenWhenTheCursorWasWritten() {
        try (var raw = REDIS.connect(client)) {
            raw.sync().set(new StateKeys(prefix).saved(key, DATE), new byte[]{1});
        }
        assertThatThrownBy(() -> store.commit(changes("2-0"))).isInstanceOf(StateIntegrityFailure.class);
        assertThat(store.cursors(Set.of(SOURCE))).containsEntry(SOURCE, "2-0");
        assertThatThrownBy(store::checkAvailable).isInstanceOf(StateIntegrityFailure.class);
    }

    @Test
    void queueOomDoesNotAdvanceTheCursorAndCanBeRetried() {
        try (var raw = REDIS.connect(client)) {
            raw.sync().configSet("maxmemory", "1");
            try {
                assertThatThrownBy(() -> store.commit(changes("3-0"))).isInstanceOf(StorageFailure.class);
                assertThat(store.cursors(Set.of(SOURCE))).isEmpty();
            } finally { raw.sync().configSet("maxmemory", "0"); }
        }
        store.commit(changes("3-0"));
        assertThat(store.cursors(Set.of(SOURCE))).containsEntry(SOURCE, "3-0");
    }

    @Test
    void malformedPrivateStateFailsFast() {
        try (var raw = REDIS.connect(client)) {
            raw.sync().set(new StateKeys(prefix).vehicle(new VehicleKey(SOURCE, "KR123")), "bad".getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> store.vehicle(new VehicleKey(SOURCE, "KR123"))).isInstanceOf(StateIntegrityFailure.class);
    }

    private StateChanges changes(String cursor) {
        return new StateChanges(Map.of(new VehicleKey(SOURCE, "KR123"), previous(2)), Set.of(),
                Map.of(key, new TripPin(HASH, BASE)), Map.of(key, forecasts()), Set.of(),
                Map.of(new EventKey(TRIP, DATE, 2), new SavedStop(10, BASE)), Map.of(TRIP, SOURCE), Map.of(SOURCE, cursor));
    }
}
