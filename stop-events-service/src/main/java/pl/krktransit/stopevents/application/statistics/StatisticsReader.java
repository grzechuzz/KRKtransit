package pl.krktransit.stopevents.application.statistics;

import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

@FunctionalInterface
public interface StatisticsReader {
    StatisticsResult read(Statistic statistic, StatisticsQuery query, QueryCancellation cancellation);
}
