package pl.krktransit.publicapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.krktransit.publicapi.application.delaymap.LineDelayMap;
import pl.krktransit.publicapi.application.schedule.*;
import pl.krktransit.publicapi.application.statistics.*;

class LineDelayMapTest {
    private static final StatisticsQuery QUERY = new StatisticsQuery("198", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), false);
    private final List<String> calls = new ArrayList<>();
    private final Schedule schedule = new Schedule();
    private final Statistics statistics = new Statistics();
    private final LineDelayMap useCase = new LineDelayMap(schedule, statistics);

    @Test
    void joinsDirectedPairsWithoutComputingMetricsOrKeepingHistoricalGeometry() {
        var points = List.of(new RoutePoint(50, 19), new RoutePoint(51, 20));
        schedule.routes = new LineRoutes(List.of(new LineDirection("End", 12, List.of(), List.of(
                new RouteSegment("a|", "b|", points), new RouteSegment("b|", "a|", points)))));
        var delays = List.of(new BandDelay(DelayBand.ALL_DAY, 1, -10, -10, -10));
        statistics.result = new DelayMapStatistics(List.of(new DelayMapSegment("a|", "b|", delays),
                new DelayMapSegment("old|", "pair|", delays)));
        var result = useCase.get(QUERY);
        assertThat(calls).containsExactly("schedule", "statistics");
        assertThat(result.directions()).singleElement().satisfies(direction -> {
            assertThat(direction.segments()).hasSize(2);
            assertThat(direction.segments().getFirst().delays()).isEqualTo(delays);
            assertThat(direction.segments().getLast().delays()).isEmpty();
            assertThat(direction.segments().getFirst().points()).isEqualTo(points);
        });
    }

    @Test
    void stillReadsStatisticsWhenThereAreNoRoutesAndPropagatesNotFound() {
        assertThat(useCase.get(QUERY).directions()).isEmpty();
        assertThat(calls).containsExactly("schedule", "statistics");
        statistics.failure = new ApiFailure.NotFound("Line", "198");
        assertThatThrownBy(() -> useCase.get(QUERY)).isSameAs(statistics.failure);
    }

    @Test
    void stopsBeforeStatisticsWhenScheduleFails() {
        schedule.failure = new ApiFailure.UpstreamUnavailable("Schedule service", null);
        assertThatThrownBy(() -> useCase.get(QUERY)).isSameAs(schedule.failure);
        assertThat(calls).containsExactly("schedule");
    }

    private final class Schedule implements ScheduleSource {
        private LineRoutes routes = new LineRoutes(List.of());
        private ApiFailure failure;
        @Override public LineRoutes lineRoutes(String lineNumber) {
            calls.add("schedule");
            assertThat(lineNumber).isEqualTo("198");
            if (failure != null) throw failure;
            return routes;
        }
        @Override public Optional<String> currentHash(FeedId feed) { throw new AssertionError(); }
        @Override public TripSummaries tripSummaries(String hash, Set<String> trips) { throw new AssertionError(); }
        @Override public Optional<TripStops> tripStops(String hash, String trip) { throw new AssertionError(); }
        @Override public Optional<Shape> shape(String hash, String shape) { throw new AssertionError(); }
    }

    private final class Statistics implements StatisticsSource {
        private DelayMapStatistics result = new DelayMapStatistics(List.of());
        private ApiFailure failure;
        @Override public DelayMapStatistics delayMap(StatisticsQuery query) {
            calls.add("statistics");
            assertThat(query).isEqualTo(QUERY);
            if (failure != null) throw failure;
            return result;
        }
        @Override public MaxDelayRanking maxDelay(StatisticsQuery query) { throw new AssertionError(); }
        @Override public RouteDelayRanking routeDelay(StatisticsQuery query) { throw new AssertionError(); }
        @Override public Punctuality punctuality(StatisticsQuery query) { throw new AssertionError(); }
        @Override public Trend trend(StatisticsQuery query) { throw new AssertionError(); }
    }
}
