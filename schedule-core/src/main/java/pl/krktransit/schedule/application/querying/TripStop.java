package pl.krktransit.schedule.application.querying;

public record TripStop(String stopId, String stopName, String stopDesc, Double latitude,
                       Double longitude, int sequence, int arrivalSeconds, int departureSeconds) {}
