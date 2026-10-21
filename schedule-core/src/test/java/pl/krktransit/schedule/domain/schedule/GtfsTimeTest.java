package pl.krktransit.schedule.domain.schedule;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GtfsTimeTest {
    @ParameterizedTest
    @CsvSource({"00:00:00,0", "25:30:00,91800", "8:00:00,28800", "' 08:30:00 ',30600", "24:00:00,86400",
            "00:59:59,3599", "596523:14:07,2147483647"})
    void preservesPythonServiceDayTime(String input, int expected) {
        assertEquals(expected, GtfsTime.parse(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"08:30", "08:60:00", "08:30:60", "-1:00:00", "8:0:00", "ab:cd:ef", "999999999:00:00",
            "08:30:", "08::00", "08:30:00:01", "596523:14:08", "2147483648:00:00"})
    void rejectsMalformedOrUnrepresentableTimes(String input) {
        assertThrows(IllegalArgumentException.class, () -> GtfsTime.parse(input));
    }
}
