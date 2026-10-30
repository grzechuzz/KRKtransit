package pl.krktransit.publicapi.application.statistics;

import java.time.LocalDate;

public record TrendDay(LocalDate date, double averageDelaySeconds, long tripsCount) {}
