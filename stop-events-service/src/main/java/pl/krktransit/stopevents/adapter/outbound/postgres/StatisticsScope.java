package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.util.List;
import java.util.Set;
import pl.krktransit.stopevents.domain.source.SourceId;

record StatisticsScope(String city, List<String> sources) {
    static StatisticsScope of(Set<SourceId> sources) {
        var cities = sources.stream().map(SourceId::cityId).distinct().toList();
        if (cities.size() != 1) throw new IllegalArgumentException("Statistics require sources from exactly one city");
        return new StatisticsScope(cities.getFirst(), sources.stream().map(SourceId::sourceId).sorted().toList());
    }
}
