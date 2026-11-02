package pl.krktransit.publicapi.application.upcoming;

@FunctionalInterface
public interface PredictionSource {
    PredictionSource DISABLED = request -> PredictedDelays.NONE;

    PredictedDelays predict(PredictionRequest request);
}
