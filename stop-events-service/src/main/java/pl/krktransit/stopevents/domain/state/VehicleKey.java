package pl.krktransit.stopevents.domain.state;

import pl.krktransit.stopevents.domain.source.SourceId;

public record VehicleKey(SourceId source, String licensePlate) {}
