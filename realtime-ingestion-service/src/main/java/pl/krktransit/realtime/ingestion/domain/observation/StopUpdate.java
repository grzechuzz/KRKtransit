package pl.krktransit.realtime.ingestion.domain.observation;

public record StopUpdate(Integer stopSequence, String stopId, StopEvent arrival, StopEvent departure,
                         Integer scheduleRelationship) {}
