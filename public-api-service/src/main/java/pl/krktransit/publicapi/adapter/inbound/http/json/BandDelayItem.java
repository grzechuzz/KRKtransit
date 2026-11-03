package pl.krktransit.publicapi.adapter.inbound.http.json;

public record BandDelayItem(String band, long samples, double meanSeconds, double medianSeconds, double p90Seconds) {}
