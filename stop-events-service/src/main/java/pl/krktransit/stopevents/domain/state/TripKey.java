package pl.krktransit.stopevents.domain.state;

import pl.krktransit.stopevents.domain.source.SourceId;

public record TripKey(SourceId source, String tripId) {}
