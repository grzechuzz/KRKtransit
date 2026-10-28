package pl.krktransit.realtime.ingestion.adapter.outbound.gtfsrt;

import java.util.Optional;
import pl.krktransit.realtime.ingestion.application.collection.EntityRejection;
import pl.krktransit.realtime.ingestion.domain.observation.StopEvent;
import pl.krktransit.realtime.ingestion.domain.observation.StopUpdate;
import pl.krktransit.realtime.ingestion.domain.observation.TripUpdateObservation;
import pl.krktransit.realtime.ingestion.domain.observation.VehicleObservation;

final class EntityFilter {
    private static final int STOP_SKIPPED = 1;
    private static final int STOP_NO_DATA = 2;
    private static final int TRIP_CANCELED = 3;
    private static final int TRIP_DELETED = 7;

    private EntityFilter() {}

    static Optional<EntityRejection> vehicle(VehicleObservation vehicle) {
        if (!hasVehicleIdentity(vehicle)) {
            return Optional.of(EntityRejection.MISSING_VEHICLE_IDENTITY);
        }
        if (!hasTimestamp(vehicle)) {
            return Optional.of(EntityRejection.MISSING_TIMESTAMP);
        }
        return Optional.empty();
    }

    static Optional<EntityRejection> tripUpdate(TripUpdateObservation update) {
        if (!hasTripIdentity(update)) {
            return Optional.of(EntityRejection.MISSING_TRIP_ID);
        }
        if (!hasUsableTripContent(update)) {
            return Optional.of(EntityRejection.NO_USABLE_STOP_UPDATES);
        }
        return Optional.empty();
    }

    static boolean isUsable(StopUpdate stop) {
        return isIdentifiable(stop) && isInformative(stop);
    }

    private static boolean hasVehicleIdentity(VehicleObservation vehicle) {
        return vehicle.vehicleId() != null || vehicle.licensePlate() != null;
    }

    private static boolean hasTimestamp(VehicleObservation vehicle) {
        return vehicle.timestamp() != null;
    }

    private static boolean hasTripIdentity(TripUpdateObservation update) {
        return update.tripId() != null;
    }

    private static boolean hasUsableTripContent(TripUpdateObservation update) {
        return !update.stopUpdates().isEmpty() || isTripRemoval(update.tripScheduleRelationship());
    }

    private static boolean isIdentifiable(StopUpdate stop) {
        return stop.stopId() != null || stop.stopSequence() != null;
    }

    private static boolean isInformative(StopUpdate stop) {
        return hasEstimate(stop.arrival()) || hasEstimate(stop.departure())
                || isWithoutEstimate(stop.scheduleRelationship());
    }

    private static boolean hasEstimate(StopEvent event) {
        return event != null && (event.time() != null || event.delay() != null);
    }

    private static boolean isWithoutEstimate(Integer relationship) {
        return relationship != null && (relationship == STOP_SKIPPED || relationship == STOP_NO_DATA);
    }

    private static boolean isTripRemoval(Integer relationship) {
        return relationship != null && (relationship == TRIP_CANCELED || relationship == TRIP_DELETED);
    }
}
