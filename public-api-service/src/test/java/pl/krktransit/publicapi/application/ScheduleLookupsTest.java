package pl.krktransit.publicapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.ShapePoint;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;

class ScheduleLookupsTest {
    private final Schedule schedule = new Schedule();
    private final ScheduleLookups lookups = new ScheduleLookups(schedule);

    @Test
    void readsTripStopsAndShapesFromTheCurrentVersionOfTheIdsSource() {
        schedule.hashes.put(new FeedId("krakow", "mpk-bus"), "hash");
        var stops = new TripStops("krakow:mpk-bus:1", "139", List.of(new TripStop("krakow:mpk-bus:s", "Rondo", null, null, null, 1, 0, 0)));
        var shape = new Shape("krakow:mpk-bus:sh:1", List.of(new ShapePoint(50, 19, 1)));
        schedule.trips.put("hash/krakow:mpk-bus:1", stops);
        schedule.shapes.put("hash/krakow:mpk-bus:sh:1", shape);

        assertThat(lookups.tripStops("krakow:mpk-bus:1")).isEqualTo(stops);
        assertThat(lookups.shape("krakow:mpk-bus:sh:1")).isEqualTo(shape);
    }

    @Test
    void unscopedIdsUnknownSourcesMissingEntitiesAndEmptyResultsAreNotFound() {
        schedule.hashes.put(new FeedId("krakow", "mpk-bus"), "hash");
        schedule.trips.put("hash/krakow:mpk-bus:empty", new TripStops("krakow:mpk-bus:empty", "139", List.of()));
        schedule.shapes.put("hash/krakow:mpk-bus:empty", new Shape("krakow:mpk-bus:empty", List.of()));

        for (var tripId : List.of("123", "krakow:mpk-bus", "krakow::1", "warsaw:bus:1", "krakow:mpk-bus:missing",
                "krakow:mpk-bus:empty")) {
            assertThatThrownBy(() -> lookups.tripStops(tripId)).isInstanceOf(ApiFailure.NotFound.class)
                    .hasMessage("Trip '" + tripId + "' not found");
        }
        assertThatThrownBy(() -> lookups.shape("krakow:mpk-bus:empty")).isInstanceOf(ApiFailure.NotFound.class)
                .hasMessage("Shape 'krakow:mpk-bus:empty' not found");
    }

    private static final class Schedule implements ScheduleSource {
        private final Map<FeedId, String> hashes = new HashMap<>();
        private final Map<String, TripStops> trips = new HashMap<>();
        private final Map<String, Shape> shapes = new HashMap<>();

        @Override
        public Optional<String> currentHash(FeedId feed) { return Optional.ofNullable(hashes.get(feed)); }

        @Override
        public TripSummaries tripSummaries(String hash, Set<String> tripIds) { return TripSummaries.none(); }

        @Override
        public Optional<TripStops> tripStops(String hash, String tripId) {
            return Optional.ofNullable(trips.get(hash + "/" + tripId));
        }

        @Override
        public Optional<Shape> shape(String hash, String shapeId) { return Optional.ofNullable(shapes.get(hash + "/" + shapeId)); }
    }
}
