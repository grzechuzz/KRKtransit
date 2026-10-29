package pl.krktransit.stopevents.application.processing;

import pl.krktransit.stopevents.domain.observation.Observation;

public record StreamEntry(String id, Observation observation) {}
