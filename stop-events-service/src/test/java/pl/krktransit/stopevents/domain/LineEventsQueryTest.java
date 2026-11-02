package pl.krktransit.stopevents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.stopevents.domain.lineevents.InvalidLineEventsQuery;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

class LineEventsQueryTest {
    private static final Instant AFTER = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    void acceptsTheDocumentedBoundsAndAZeroCursorId() {
        assertThat(query("krakow", "139", OptionalLong.of(0), 1).pageSize()).isEqualTo(1);
        assertThat(query("krakow", "N1", OptionalLong.empty(), LineEventsQuery.MAXIMUM_PAGE_SIZE).pageSize()).isEqualTo(5_000);
        assertThat(LineEventsQuery.DEFAULT_PAGE_SIZE).isEqualTo(2_000);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 5_001})
    void rejectsPageSizesOutsideOneToFiveThousand(int pageSize) {
        assertInvalid(() -> query("krakow", "139", OptionalLong.empty(), pageSize));
    }

    @Test
    void requiresCityLineAndCreatedAfter() {
        assertInvalid(() -> query(null, "139", OptionalLong.empty(), 10));
        assertInvalid(() -> query("Krakow", "139", OptionalLong.empty(), 10));
        assertInvalid(() -> query("krakow", null, OptionalLong.empty(), 10));
        assertInvalid(() -> query("krakow", "x' OR 1=1", OptionalLong.empty(), 10));
        assertInvalid(() -> query("krakow", "123456", OptionalLong.empty(), 10));
        assertInvalid(() -> new LineEventsQuery("krakow", "139", null, OptionalLong.empty(), 10));
    }

    @Test
    void rejectsANegativeCursorId() {
        assertInvalid(() -> query("krakow", "139", OptionalLong.of(-1), 10));
    }

    private static LineEventsQuery query(String city, String line, OptionalLong afterId, int pageSize) {
        return new LineEventsQuery(city, line, AFTER, afterId, pageSize);
    }

    private static void assertInvalid(Runnable creation) {
        assertThatThrownBy(creation::run).isInstanceOf(InvalidLineEventsQuery.class);
    }
}
