package pl.krktransit.schedule.domain.schedule;

public record Stop(String id, String name, String code,
                   String description, Double latitude, Double longitude) {}
