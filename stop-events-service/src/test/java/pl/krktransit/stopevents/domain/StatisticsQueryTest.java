package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult.Punctuality;

class StatisticsQueryTest {
    private static final LocalDate START = LocalDate.of(2026, 3, 19);

    @ParameterizedTest
    @CsvSource({"1,30", "2,60", "6,60", "7,300", "30,300", "366,300"})
    void cacheTtlUsesInclusiveDaysWithoutSpecialCasingToday(int days, int seconds) {
        var query = new StatisticsQuery("139", START, START.plusDays(days - 1), false);
        assertThat(query.cacheTtl()).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void preservesPythonDateAndLineValidation() {
        assertThatThrownBy(() -> new StatisticsQuery("139 OR 1=1", START, START, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatisticsQuery("139", START, START.minusDays(1), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatisticsQuery("139", START, START.plusDays(366), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatisticsQuery("139", null, START, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void punctualityKeepsZeroCountsAndRoundsPercentagesLikeTheExistingApi() {
        var empty = new Punctuality(0, 0, 0, 0);
        assertThat(empty.onTimePercent()).isZero();
        var result = new Punctuality(16, 1, 3, 12);
        assertThat(result.onTimePercent()).isEqualTo(6.2);
        assertThat(result.slightlyDelayedPercent()).isEqualTo(18.8);
        assertThat(result.delayedPercent()).isEqualTo(75.0);
        assertThat(new Punctuality(2000, 363, 0, 1637).onTimePercent()).isEqualTo(18.1);
        assertThat(new Punctuality(2000, 77, 0, 1923).onTimePercent()).isEqualTo(3.9);
        assertThat(new Punctuality(2000, 29, 0, 1971).onTimePercent()).isEqualTo(1.5);
    }
}
