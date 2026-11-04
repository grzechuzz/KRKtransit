package pl.krktransit.publicapi.application.statistics;

public record BandDelay(DelayBand band, long samples, double meanSeconds, double medianSeconds, double p90Seconds) {}
