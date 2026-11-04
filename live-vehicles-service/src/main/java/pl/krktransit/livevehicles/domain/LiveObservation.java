package pl.krktransit.livevehicles.domain;

public sealed interface LiveObservation permits VehiclePosition, TripForecast {}
