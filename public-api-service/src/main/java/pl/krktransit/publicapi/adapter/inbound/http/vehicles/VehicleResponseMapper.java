package pl.krktransit.publicapi.adapter.inbound.http.vehicles;

import java.util.List;
import java.util.Objects;
import pl.krktransit.publicapi.adapter.inbound.http.ResponseTimes;
import pl.krktransit.publicapi.adapter.inbound.http.json.LiveVehicleItem;
import pl.krktransit.publicapi.adapter.inbound.http.json.LiveVehicleResponse;
import pl.krktransit.publicapi.application.vehicles.LiveVehicle;

final class VehicleResponseMapper {
    LiveVehicleResponse vehicles(List<LiveVehicle> vehicles) {
        return new LiveVehicleResponse(vehicles.size(), vehicles.stream().map(this::vehicle).toList());
    }

    private LiveVehicleItem vehicle(LiveVehicle vehicle) {
        return new LiveVehicleItem(vehicle.tripId(), vehicle.licensePlate(), vehicle.lineNumber(),
                Objects.requireNonNullElse(vehicle.headsign(), ""), vehicle.shapeId(), vehicle.latitude(),
                vehicle.longitude(), vehicle.bearing(), ResponseTimes.utc(vehicle.timestamp()));
    }
}
