package pl.krktransit.publicapi.adapter.inbound.http.json;

public record DelayMapStopItem(String stopKey, String stopName, String stopDesc, double latitude, double longitude) {}
