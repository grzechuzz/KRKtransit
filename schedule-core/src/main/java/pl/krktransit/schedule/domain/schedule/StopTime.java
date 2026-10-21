package pl.krktransit.schedule.domain.schedule;

public record StopTime(String tripId, int sequence, String stopId, int arrivalSeconds, int departureSeconds) {}
