package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.VehicleStopStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.ingestion.application.collection.DecodeFailure;
import pl.krktransit.realtime.ingestion.application.collection.DecodedFeed;
import pl.krktransit.realtime.ingestion.application.collection.EntityRejection;
import pl.krktransit.realtime.ingestion.application.collection.FetchedFeed;
import pl.krktransit.realtime.ingestion.domain.observation.ObservationOrigin;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleLocation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;
import pl.krktransit.realtime.ingestion.domain.source.Endpoint;
import pl.krktransit.realtime.ingestion.support.Endpoints;

class GtfsRealtimeDecoderTest {
    private static final Endpoint VEHICLES = Endpoints.vehiclePositions(Endpoints.MPK_TRAM);
    private static final Endpoint TRIPS = Endpoints.tripUpdates(Endpoints.MPK_TRAM);
    private static final ObservationOrigin ORIGIN = new ObservationOrigin(Endpoints.MPK_TRAM, Endpoints.FETCHED_AT,
            1_761_387_300L);
    private final GtfsRealtimeDecoder decoder = new GtfsRealtimeDecoder();

    @Test
    void copiesVehiclePositionFieldsWithTheSharedOrigin() {
        var vehicle = minimalVehicle()
                .setTrip(TripDescriptor.newBuilder().setTripId("block_1_trip_2"))
                .setVehicle(VehicleDescriptor.newBuilder().setId("0042").setLicensePlate("RZ 101"))
                .setPosition(Position.newBuilder().setLatitude(50.06f).setLongitude(19.94f).setBearing(181.5f))
                .setStopId("stop_77").setCurrentStopSequence(12).setCurrentStatus(VehicleStopStatus.STOPPED_AT);

        var decoded = decoder.decode(feed(VEHICLES, vehicles(vehicle)));

        assertThat(decoded.observations()).containsExactly(new VehicleObservation(ORIGIN, "block_1_trip_2", "0042",
                "RZ 101", new VehicleLocation(50.06f, 19.94f, 181.5f), "stop_77", 12, 1, 1_761_387_290L));
        assertThat(decoded.rejections()).isEmpty();
    }

    @Test
    void keepsVehiclesWithoutTripPositionOrPlateWhenTheyAreIdentifiable() {
        var decoded = decoder.decode(feed(VEHICLES, vehicles(minimalVehicle())));

        assertThat(decoded.observations()).containsExactly(new VehicleObservation(ORIGIN, null, "0042", null, null,
                null, null, null, 1_761_387_290L));
    }

    @Test
    void preservesZeroValuedStatusSequenceAndBearing() {
        var vehicle = minimalVehicle().setCurrentStatus(VehicleStopStatus.INCOMING_AT).setCurrentStopSequence(0)
                .setPosition(Position.newBuilder().setLatitude(0).setLongitude(0).setBearing(0));

        var observation = onlyVehicle(vehicle);

        assertThat(observation.currentStatus()).isZero();
        assertThat(observation.currentStopSequence()).isZero();
        assertThat(observation.location()).isEqualTo(new VehicleLocation(0f, 0f, 0f));
    }

    @Test
    void treatsBlankIdentifiersAndZeroTimestampsAsAbsent() {
        var vehicle = minimalVehicle().setTrip(TripDescriptor.newBuilder().setTripId(" "))
                .setVehicle(VehicleDescriptor.newBuilder().setId("0042").setLicensePlate("")).setStopId("");

        var observation = onlyVehicle(vehicle);

        assertThat(observation.tripId()).isNull();
        assertThat(observation.licensePlate()).isNull();
        assertThat(observation.stopId()).isNull();
    }

    @Test
    void keepsNonblankIdentifierBytesExactly() {
        var vehicle = minimalVehicle().setTrip(TripDescriptor.newBuilder().setTripId(" 007:a "));

        assertThat(onlyVehicle(vehicle).tripId()).isEqualTo(" 007:a ");
    }

    @Test
    void dropsIncompleteOrInvalidPositionsButKeepsTheVehicle() {
        var partial = minimalVehicle().setPosition(Position.newBuilder().setLatitude(50.1f).buildPartial());
        var outOfRange = minimalVehicle().setPosition(Position.newBuilder().setLatitude(95f).setLongitude(19f));
        var notANumber = minimalVehicle().setPosition(Position.newBuilder().setLatitude(Float.NaN).setLongitude(19f));
        var badBearing = minimalVehicle().setPosition(Position.newBuilder().setLatitude(50f).setLongitude(19f)
                .setBearing(Float.POSITIVE_INFINITY));

        assertThat(onlyVehicle(partial).location()).isNull();
        assertThat(onlyVehicle(outOfRange).location()).isNull();
        assertThat(onlyVehicle(notANumber).location()).isNull();
        assertThat(onlyVehicle(badBearing).location()).isEqualTo(new VehicleLocation(50f, 19f, null));
    }

    @Test
    void rejectsUnattributableOrUntimedVehicles() {
        var anonymous = VehiclePosition.newBuilder().setTimestamp(1_761_387_290L)
                .setVehicle(VehicleDescriptor.newBuilder().setId("").setLicensePlate(" "));
        var untimed = VehiclePosition.newBuilder().setVehicle(VehicleDescriptor.newBuilder().setLicensePlate("KR 1"));
        var zeroTime = minimalVehicle().setTimestamp(0);
        byte[] body = message(entity(anonymous.buildPartial()), entity(untimed.buildPartial()),
                entity(zeroTime.buildPartial()), entity(minimalVehicle().buildPartial()));

        DecodedFeed decoded = decoder.decode(feed(VEHICLES, body));

        assertThat(decoded.observations()).hasSize(1);
        assertThat(decoded.rejections()).containsOnly(Map.entry(EntityRejection.MISSING_VEHICLE_IDENTITY, 1),
                Map.entry(EntityRejection.MISSING_TIMESTAMP, 2));
        assertThat(decoded.rejectedEntities()).isEqualTo(3);
    }

    @Test
    void preservesUnknownVehicleStatusNumbers() {
        var vehicle = minimalVehicle().setUnknownFields(varint(4, 7));

        assertThat(onlyVehicle(vehicle).currentStatus()).isEqualTo(7);
    }

    @Test
    void keepsEveryStopUpdateThatIdentifiesAStopAndCarriesAFact() {
        var update = TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId("trip_9")
                        .setScheduleRelationship(TripDescriptor.ScheduleRelationship.SCHEDULED))
                .setVehicle(VehicleDescriptor.newBuilder().setId("0042"))
                .setTimestamp(1_761_387_280L)
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopSequence(0).setStopId("s1")
                        .setArrival(StopTimeEvent.newBuilder().setTime(1_761_387_400L))
                        .setDeparture(StopTimeEvent.newBuilder().setTime(1_761_387_430L).setDelay(30)))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopSequence(2)
                        .setArrival(StopTimeEvent.newBuilder().setDelay(0)))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("s3")
                        .setScheduleRelationship(StopTimeUpdate.ScheduleRelationship.SKIPPED));

        var decoded = decoder.decode(feed(TRIPS, tripUpdates(update)));

        assertThat(decoded.observations()).containsExactly(new TripUpdateObservation(ORIGIN, "trip_9", 0, "0042",
                1_761_387_280L, List.of(
                new StopUpdate(0, "s1", new StopEvent(1_761_387_400L, null), new StopEvent(1_761_387_430L, 30), null),
                new StopUpdate(2, null, new StopEvent(null, 0), null, null),
                new StopUpdate(null, "s3", null, null, 1))));
    }

    @Test
    void dropsStopUpdatesWithoutStopReferenceOrAnyEstimate() {
        var update = trip("trip").addStopTimeUpdate(StopTimeUpdate.newBuilder()
                        .setArrival(StopTimeEvent.newBuilder().setTime(1_761_387_400L)))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("no-estimate"))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("zero-time")
                        .setArrival(StopTimeEvent.newBuilder().setTime(0)))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("kept")
                        .setDeparture(StopTimeEvent.newBuilder().setTime(1_761_387_500L))
                        .setArrival(StopTimeEvent.newBuilder()));

        var observation = (TripUpdateObservation) decoder.decode(feed(TRIPS, tripUpdates(update))).observations()
                .getFirst();

        assertThat(observation.stopUpdates()).containsExactly(
                new StopUpdate(null, "kept", null, new StopEvent(1_761_387_500L, null), null));
    }

    @Test
    void keepsCancelledTripsWithoutStopUpdatesAndAbsentTimestamp() {
        var update = TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId("cancelled")
                .setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED));

        var observation = (TripUpdateObservation) decoder.decode(feed(TRIPS, tripUpdates(update))).observations()
                .getFirst();

        assertThat(observation.tripScheduleRelationship()).isEqualTo(3);
        assertThat(observation.timestamp()).isNull();
        assertThat(observation.vehicleId()).isNull();
        assertThat(observation.stopUpdates()).isEmpty();
    }

    @Test
    void rejectsTripUpdatesWithoutTripOrUsableStops() {
        var withoutTrip = trip(" ").addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("s")
                .setArrival(StopTimeEvent.newBuilder().setTime(1_761_387_400L)));
        var withoutStops = trip("empty");
        var onlyUnusableStops = trip("unusable").addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("s"));
        byte[] body = message(entity(withoutTrip.buildPartial()), entity(withoutStops.buildPartial()),
                entity(onlyUnusableStops.buildPartial()));

        DecodedFeed decoded = decoder.decode(feed(TRIPS, body));

        assertThat(decoded.observations()).isEmpty();
        assertThat(decoded.rejections()).containsOnly(Map.entry(EntityRejection.MISSING_TRIP_ID, 1),
                Map.entry(EntityRejection.NO_USABLE_STOP_UPDATES, 2));
    }

    @Test
    void preservesUnknownScheduleRelationshipNumbers() {
        var update = TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId("t").setUnknownFields(varint(4, 42)))
                .addStopTimeUpdate(StopTimeUpdate.newBuilder().setStopId("s").setUnknownFields(varint(5, 99))
                        .setArrival(StopTimeEvent.newBuilder().setDelay(5)));

        var observation = (TripUpdateObservation) decoder.decode(feed(TRIPS, tripUpdates(update))).observations()
                .getFirst();

        assertThat(observation.tripScheduleRelationship()).isEqualTo(42);
        assertThat(observation.stopUpdates().getFirst().scheduleRelationship()).isEqualTo(99);
    }

    @Test
    void keepsProviderOrderAndOnlyTheEndpointKind() {
        byte[] body = message(entity(minimalVehicle().setStopId("a").buildPartial()),
                FeedEntity.newBuilder().setId("t").setTripUpdate(trip("t").addStopTimeUpdate(StopTimeUpdate.newBuilder()
                        .setStopId("s").setArrival(StopTimeEvent.newBuilder().setDelay(1))).buildPartial()).buildPartial(),
                entity(minimalVehicle().setStopId("b").buildPartial()),
                FeedEntity.newBuilder().setId("alert").buildPartial());

        var vehicles = decoder.decode(feed(VEHICLES, body)).observations();
        var trips = decoder.decode(feed(TRIPS, body)).observations();

        assertThat(vehicles).extracting(observation -> ((VehicleObservation) observation).stopId())
                .containsExactly("a", "b");
        assertThat(trips).hasSize(1);
    }

    @Test
    void validFeedWithoutEntitiesYieldsNoObservations() {
        var decoded = decoder.decode(feed(VEHICLES, header().build().toByteArray()));

        assertThat(decoded.observations()).isEmpty();
        assertThat(decoded.rejections()).isEmpty();
    }

    @Test
    void absentOrZeroHeaderTimestampIsAbsent() {
        byte[] absent = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
                .addEntity(entity(minimalVehicle().buildPartial())).build().toByteArray();
        byte[] zero = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")
                .setTimestamp(0)).addEntity(entity(minimalVehicle().buildPartial())).build().toByteArray();

        assertThat(decoder.decode(feed(VEHICLES, absent)).observations().getFirst().origin().feedTimestamp()).isNull();
        assertThat(decoder.decode(feed(VEHICLES, zero)).observations().getFirst().origin().feedTimestamp()).isNull();
    }

    @Test
    void rejectsMalformedProtobuf() {
        assertThatThrownBy(() -> decoder.decode(feed(VEHICLES, new byte[]{(byte) 0x0a, (byte) 0xff, 0x01})))
                .isInstanceOf(DecodeFailure.class).hasMessageContaining("Malformed");
    }

    @Test
    void rejectsEmptyBodiesAndFeedsWithoutHeader() {
        assertThatThrownBy(() -> decoder.decode(feed(TRIPS, new byte[0])))
                .isInstanceOf(DecodeFailure.class).hasMessageContaining("header");
        byte[] withoutVersion = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setTimestamp(1).buildPartial())
                .buildPartial().toByteArray();
        assertThatThrownBy(() -> decoder.decode(feed(TRIPS, withoutVersion))).isInstanceOf(DecodeFailure.class);
    }

    private VehicleObservation onlyVehicle(VehiclePosition.Builder vehicle) {
        var decoded = decoder.decode(feed(VEHICLES, vehicles(vehicle)));
        assertThat(decoded.rejections()).isEmpty();
        return (VehicleObservation) decoded.observations().getFirst();
    }

    private static VehiclePosition.Builder minimalVehicle() {
        return VehiclePosition.newBuilder().setVehicle(VehicleDescriptor.newBuilder().setId("0042"))
                .setTimestamp(1_761_387_290L);
    }

    private static TripUpdate.Builder trip(String tripId) {
        return TripUpdate.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId(tripId));
    }

    private static FetchedFeed feed(Endpoint endpoint, byte[] body) {
        return new FetchedFeed(endpoint, Endpoints.FETCHED_AT, body);
    }

    private static byte[] vehicles(VehiclePosition.Builder vehicle) {
        return message(entity(vehicle.buildPartial()));
    }

    private static byte[] tripUpdates(TripUpdate.Builder update) {
        return message(FeedEntity.newBuilder().setId("e").setTripUpdate(update.buildPartial()).buildPartial());
    }

    private static FeedEntity entity(VehiclePosition vehicle) {
        return FeedEntity.newBuilder().setId("e").setVehicle(vehicle).buildPartial();
    }

    private static FeedEntity entity(TripUpdate update) {
        return FeedEntity.newBuilder().setId("e").setTripUpdate(update).buildPartial();
    }

    private static byte[] message(FeedEntity... entities) {
        var message = header();
        for (FeedEntity entity : entities) message.addEntity(entity);
        return message.buildPartial().toByteArray();
    }

    private static FeedMessage.Builder header() {
        return FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")
                .setTimestamp(1_761_387_300L));
    }

    private static UnknownFieldSet varint(int field, long value) {
        return UnknownFieldSet.newBuilder()
                .addField(field, UnknownFieldSet.Field.newBuilder().addVarint(value).build()).build();
    }
}
