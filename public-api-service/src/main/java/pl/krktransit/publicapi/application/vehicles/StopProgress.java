package pl.krktransit.publicapi.application.vehicles;

public record StopProgress(Integer sequence, VehicleStopStatus status) {
    public static final StopProgress NONE = new StopProgress(null, null);
}
