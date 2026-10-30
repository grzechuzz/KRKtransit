package pl.krktransit.publicapi.adapter.inbound.http.json;

public record LiveVehicleItem(String tripId, String licensePlate, String lineNumber, String headsign, String shapeId,
                              double latitude, double longitude, Double bearing, String timestamp) {}
