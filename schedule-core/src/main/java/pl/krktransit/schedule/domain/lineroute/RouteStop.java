package pl.krktransit.schedule.domain.lineroute;

public record RouteStop(StopKey key, String stopName, String stopDesc, double latitude, double longitude) {}
