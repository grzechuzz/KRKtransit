package pl.krktransit.publicapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.INCOMING_AT;
import static pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.IN_TRANSIT_TO;
import static pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.STOPPED_AT;
import static pl.krktransit.publicapi.application.vehicles.VehicleStopStatus.UNRECOGNIZED;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.schedule.FeedId;
import pl.krktransit.publicapi.application.schedule.ScheduleLookups;
import pl.krktransit.publicapi.application.schedule.ScheduleSource;
import pl.krktransit.publicapi.application.schedule.Shape;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.schedule.TripStops;
import pl.krktransit.publicapi.application.schedule.TripSummaries;
import pl.krktransit.publicapi.application.upcoming.TripProgress;
import pl.krktransit.publicapi.application.upcoming.UpcomingStop;
import pl.krktransit.publicapi.application.upcoming.UpcomingStops;
import pl.krktransit.publicapi.application.upcoming.UpcomingTrip;
import pl.krktransit.publicapi.application.vehicles.PositionSnapshot;
import pl.krktransit.publicapi.application.vehicles.StopProgress;
import pl.krktransit.publicapi.application.vehicles.VehiclePosition;
import pl.krktransit.publicapi.application.vehicles.VehiclePositionsSource;
import pl.krktransit.publicapi.application.vehicles.VehicleStopStatus;
import pl.krktransit.publicapi.support.MutableClock;

class UpcomingStopsTest {
    private static final FeedId BUS = new FeedId("krakow", "mpk-bus");
    private static final String TRIP = "krakow:mpk-bus:trip";
    private static final Instant OBSERVED = Instant.parse("2026-11-02T13:00:00Z");
    private final MutableClock clock = new MutableClock(OBSERVED);
    private final Positions positions = new Positions();
    private final Schedule schedule = new Schedule();
    private final UpcomingStops upcoming = new UpcomingStops(new PositionSnapshot(positions, Duration.ofSeconds(3), clock),
            new ScheduleLookups(schedule));

    @Test
    void travellingTowardsAStopListsItAndTheFourAfterIt() {
        tripWithSequences(1, 2, 3, 4, 5, 6, 7, 8);
        drive(3, IN_TRANSIT_TO);

        var trip = upcoming.forTrip(TRIP);

        assertThat(trip.progress()).isEqualTo(TripProgress.IN_PROGRESS);
        assertThat(trip.observedAt()).isEqualTo(OBSERVED);
        assertThat(sequences(trip)).containsExactly(3, 4, 5, 6, 7);
        assertThat(trip.stops().getFirst().scheduledArrival()).isEqualTo(Instant.parse("2026-11-02T12:03:00Z"));
        drive(3, INCOMING_AT);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(3, 4, 5, 6, 7);
    }

    @Test
    void stoppedAtAStopListsOnlyTheStopsAfterIt() {
        tripWithSequences(1, 2, 3, 4, 5, 6, 7, 8);
        drive(3, STOPPED_AT);

        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 5, 6, 7, 8);
    }

    @Test
    void theEndOfTheTripShortensTheListAndStoppingAtTheTerminalFinishesIt() {
        tripWithSequences(1, 2, 3, 4, 5, 6, 7, 8);
        drive(7, STOPPED_AT);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(8);

        drive(8, IN_TRANSIT_TO);
        assertThat(upcoming.forTrip(TRIP).progress()).isEqualTo(TripProgress.IN_PROGRESS);

        drive(8, STOPPED_AT);
        var finished = upcoming.forTrip(TRIP);
        assertThat(finished.progress()).isEqualTo(TripProgress.FINISHED);
        assertThat(finished.stops()).isEmpty();
    }

    @Test
    void theReportedStopIdIdentifiesTheTargetWhenTheSequenceNamesTheLastPassedStop() {
        tripWithSequences(1, 2, 3, 4, 5, 6, 7, 8);
        report("s4", 3, IN_TRANSIT_TO);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 5, 6, 7, 8);
        report("s4", 3, INCOMING_AT);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 5, 6, 7, 8);
        report("s3", 3, STOPPED_AT);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 5, 6, 7, 8);
        report("s4", 4, IN_TRANSIT_TO);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 5, 6, 7, 8);
    }

    @Test
    void theSequenceChoosesBetweenRepeatedOccurrencesOfTheReportedStop() {
        schedule.trip(List.of(stop("x", 1, 0), stop("a", 2, 60), stop("y", 3, 120), stop("a", 4, 180), stop("z", 9, 240)));
        report("a", 1, IN_TRANSIT_TO);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(2, 3, 4, 9);
        report("a", 3, IN_TRANSIT_TO);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(4, 9);
        report("a", 8, STOPPED_AT);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(9);
    }

    @Test
    void anUnknownOrAmbiguousStopIdIsUnknownProgress() {
        schedule.trip(List.of(stop("x", 1, 0), stop("a", 2, 60), stop("a", 3, 120)));
        report("missing", 1, IN_TRANSIT_TO);
        assertThat(upcoming.forTrip(TRIP).progress()).isEqualTo(TripProgress.UNKNOWN);
        report("a", null, IN_TRANSIT_TO);
        assertThat(upcoming.forTrip(TRIP).progress()).isEqualTo(TripProgress.UNKNOWN);
        report("x", null, IN_TRANSIT_TO);
        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(1, 2, 3);
    }

    @Test
    void anAbsentStatusFollowsTheGtfsRealtimeInTransitDefault() {
        tripWithSequences(1, 2, 3, 4);
        drive(2, null);

        assertThat(sequences(upcoming.forTrip(TRIP))).containsExactly(2, 3, 4);
    }

    @Test
    void unusableProgressIsReportedAsUnknownWithoutPretendingTheTripStarts() {
        tripWithSequences(1, 2, 3, 4);
        for (var progress : List.of(new StopProgress(null, 2, UNRECOGNIZED), new StopProgress(null, null, IN_TRANSIT_TO),
                new StopProgress(null, 9, IN_TRANSIT_TO), StopProgress.NONE)) {
            positions.current = List.of(vehicle("DN041", OBSERVED, progress));
            clock.advance(Duration.ofSeconds(3));

            var trip = upcoming.forTrip(TRIP);

            assertThat(trip.progress()).isEqualTo(TripProgress.UNKNOWN);
            assertThat(trip.stops()).isEmpty();
        }
    }

    @Test
    void stopsFollowTheScheduleOrderAcrossSequenceGapsAndRepeatedStops() {
        schedule.trip(List.of(stop("a", 1, 0), stop("b", 3, 60), stop("a", 7, 120), stop("c", 8, 180)));
        drive(3, STOPPED_AT);

        assertThat(upcoming.forTrip(TRIP).stops()).extracting(stop -> stop.stop().stopId())
                .containsExactly("krakow:mpk-bus:a", "krakow:mpk-bus:c");
    }

    @Test
    void nightTripsAfterMidnightKeepThePreviousServiceDate() {
        schedule.trip(List.of(stop("a", 1, 87_900), stop("b", 2, 88_500), stop("c", 3, 89_100)));
        positions.current = List.of(vehicle("DN041", Instant.parse("2026-11-02T23:30:00Z"),
                new StopProgress(null, 2, IN_TRANSIT_TO)));

        assertThat(upcoming.forTrip(TRIP).stops()).extracting(UpcomingStop::scheduledArrival)
                .containsExactly(Instant.parse("2026-11-02T23:35:00Z"), Instant.parse("2026-11-02T23:45:00Z"));
    }

    @Test
    void theNewestVehicleOnTheTripProvidesTheProgress() {
        tripWithSequences(1, 2, 3, 4, 5);
        positions.current = List.of(vehicle("DN041", OBSERVED.minusSeconds(30), new StopProgress(null, 4, IN_TRANSIT_TO)),
                vehicle("DN042", OBSERVED, new StopProgress(null, 2, IN_TRANSIT_TO)));

        var trip = upcoming.forTrip(TRIP);

        assertThat(trip.observedAt()).isEqualTo(OBSERVED);
        assertThat(sequences(trip)).containsExactly(2, 3, 4, 5);
    }

    @Test
    void missingVehiclesAndTripsAreNotFoundButUpstreamFailuresAreNot() {
        assertThatThrownBy(() -> upcoming.forTrip(TRIP)).isInstanceOf(ApiFailure.NotFound.class)
                .hasMessage("Vehicle on trip '" + TRIP + "' not found");

        drive(2, IN_TRANSIT_TO);
        assertThatThrownBy(() -> upcoming.forTrip(TRIP)).isInstanceOf(ApiFailure.NotFound.class)
                .hasMessage("Trip '" + TRIP + "' not found");

        schedule.unavailable = true;
        assertThatThrownBy(() -> upcoming.forTrip(TRIP)).isInstanceOf(ApiFailure.UpstreamUnavailable.class);

        positions.failure = new ApiFailure.UpstreamBusy("Live vehicles service", null);
        clock.advance(Duration.ofSeconds(3));
        assertThatThrownBy(() -> upcoming.forTrip(TRIP)).isInstanceOf(ApiFailure.UpstreamBusy.class);
    }

    private void tripWithSequences(int... sequences) {
        schedule.trip(IntStream.of(sequences).mapToObj(sequence -> stop("s" + sequence, sequence, 13 * 3600 + sequence * 60))
                .toList());
    }

    private void drive(int sequence, VehicleStopStatus status) {
        positions.current = List.of(vehicle("DN041", OBSERVED, new StopProgress(null, sequence, status)));
        clock.advance(Duration.ofSeconds(3));
    }

    private void report(String stop, Integer sequence, VehicleStopStatus status) {
        positions.current = List.of(vehicle("DN041", OBSERVED, new StopProgress("krakow:mpk-bus:" + stop, sequence, status)));
        clock.advance(Duration.ofSeconds(3));
    }

    private static List<Integer> sequences(UpcomingTrip trip) {
        return trip.stops().stream().map(stop -> stop.stop().sequence()).toList();
    }

    private static TripStop stop(String id, int sequence, int arrivalSeconds) {
        return new TripStop("krakow:mpk-bus:" + id, id, null, null, null, sequence, arrivalSeconds, arrivalSeconds);
    }

    private static VehiclePosition vehicle(String plate, Instant timestamp, StopProgress progress) {
        return new VehiclePosition(BUS, TRIP, plate, 50.06, 19.94, null, timestamp, progress);
    }

    private static final class Positions implements VehiclePositionsSource {
        private List<VehiclePosition> current = List.of();
        private ApiFailure failure;

        @Override
        public List<VehiclePosition> positions() {
            if (failure != null) throw failure;
            return current;
        }
    }

    private static final class Schedule implements ScheduleSource {
        private final Map<String, TripStops> trips = new HashMap<>();
        private boolean unavailable;

        void trip(List<TripStop> stops) { trips.put(TRIP, new TripStops(TRIP, new ArrayList<>(stops))); }

        @Override
        public Optional<String> currentHash(FeedId feed) {
            if (unavailable) throw new ApiFailure.UpstreamUnavailable("Schedule service", null);
            return Optional.of("hash");
        }

        @Override
        public TripSummaries tripSummaries(String hash, Set<String> tripIds) { return new TripSummaries(Map.of(), Set.of()); }

        @Override
        public Optional<TripStops> tripStops(String hash, String tripId) { return Optional.ofNullable(trips.get(tripId)); }

        @Override
        public Optional<Shape> shape(String hash, String shapeId) { return Optional.empty(); }
    }
}
