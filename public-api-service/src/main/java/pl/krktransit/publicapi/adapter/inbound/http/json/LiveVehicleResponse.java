package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record LiveVehicleResponse(int count, List<LiveVehicleItem> vehicles) {
    public LiveVehicleResponse { vehicles = List.copyOf(vehicles); }
}
