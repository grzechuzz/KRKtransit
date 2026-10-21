package pl.krktransit.schedule.domain.schedule;

public record Trip(String id, String routeId, String serviceId,
                   Integer directionId, String headsign, String shapeId) {}
