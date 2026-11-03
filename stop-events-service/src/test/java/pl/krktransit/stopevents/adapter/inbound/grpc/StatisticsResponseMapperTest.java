package pl.krktransit.stopevents.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.contracts.v1.StatisticsRequest;
import pl.krktransit.stopevents.domain.statistics.DelayBand;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

class StatisticsResponseMapperTest {
    @Test
    void mapsAllBandsAndPreservesTheQueryAndSignedMetrics() {
        var query = StatisticsRequest.newBuilder().setLineNumber("198").setStartDate("2026-10-01")
                .setEndDate("2026-10-07").setIncludeEstimated(true).build();
        var bands = Arrays.stream(DelayBand.values()).map(band -> new StatisticsResult.BandDelay(band, 2, -1.5, -2, 3.1)).toList();
        var result = new StatisticsResult.DelayMap(List.of(new StatisticsResult.MapSegment("a|01", "b|", bands)));
        var response = new StatisticsResponseMapper().delayMap(query, result);
        assertThat(response.getQuery()).isEqualTo(query);
        assertThat(response.getSegmentsList()).singleElement().satisfies(segment -> {
            assertThat(segment.getFromStopKey()).isEqualTo("a|01");
            assertThat(segment.getToStopKey()).isEqualTo("b|");
            assertThat(segment.getDelaysList()).hasSize(6).allSatisfy(delay -> {
                assertThat(delay.hasBand()).isTrue();
                assertThat(delay.getSamples()).isEqualTo(2);
                assertThat(delay.getMeanSeconds()).isEqualTo(-1.5);
                assertThat(delay.getMedianSeconds()).isEqualTo(-2);
                assertThat(delay.getP90Seconds()).isEqualTo(3.1);
            });
        });
    }
}
