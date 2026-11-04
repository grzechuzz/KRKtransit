package pl.krktransit.publicapi.application.upcoming;

@FunctionalInterface
public interface OperatorForecastSource {
    OperatorForecast forecast(String tripId);
}
