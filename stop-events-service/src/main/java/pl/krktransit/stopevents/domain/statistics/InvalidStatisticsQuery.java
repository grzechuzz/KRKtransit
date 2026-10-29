package pl.krktransit.stopevents.domain.statistics;

public final class InvalidStatisticsQuery extends IllegalArgumentException {
    public InvalidStatisticsQuery(String message) { super(message); }

    public InvalidStatisticsQuery(String message, Throwable cause) { super(message, cause); }
}
