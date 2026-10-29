package pl.krktransit.stopevents.domain.schedule;

public record ScheduledStop(int sequence, String stopId, String name, String description, int arrivalSeconds) {}
