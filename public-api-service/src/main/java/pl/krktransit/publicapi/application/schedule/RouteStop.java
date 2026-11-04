package pl.krktransit.publicapi.application.schedule;

public record RouteStop(String stopKey, String stopName, String stopDesc, double latitude, double longitude) {}
