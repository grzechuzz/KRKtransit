package pl.krktransit.publicapi.application.vehicles;

import java.time.Instant;

public record LiveVehicle(String tripId, String licensePlate, String lineNumber, String headsign, String shapeId,
                          double latitude, double longitude, Double bearing, Instant timestamp) {}
