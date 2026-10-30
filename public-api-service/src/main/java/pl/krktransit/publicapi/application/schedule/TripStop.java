package pl.krktransit.publicapi.application.schedule;

public record TripStop(String stopId, String stopName, String stopDescription, Double latitude, Double longitude,
                       int sequence) {}
