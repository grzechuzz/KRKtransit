package pl.krktransit.publicapi.adapter.inbound.http.json;

public record TrendDayItem(String date, double avgDelaySeconds, long tripsCount) {}
