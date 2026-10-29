package pl.krktransit.stopevents.domain.event;

import java.time.LocalDate;

public record EventKey(String tripId, LocalDate serviceDate, int sequence) {}
