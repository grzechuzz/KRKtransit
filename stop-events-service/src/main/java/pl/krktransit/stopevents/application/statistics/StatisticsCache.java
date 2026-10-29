package pl.krktransit.stopevents.application.statistics;

import java.util.Optional;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

public interface StatisticsCache {
    Optional<StatisticsResult> get(Statistic statistic, StatisticsQuery query);
    void put(Statistic statistic, StatisticsQuery query, StatisticsResult result);
}
