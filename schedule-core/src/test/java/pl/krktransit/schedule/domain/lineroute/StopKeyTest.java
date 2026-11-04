package pl.krktransit.schedule.domain.lineroute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StopKeyTest {
    @Test
    void normalizesNamesAndDescriptionsWithoutTrimming() {
        assertEquals("ur al. 29 listopada|01", StopKey.of("UR Al. 29 Listopada", "01").value());
        assertEquals("zażółć gęślą|a", StopKey.of("ZAŻÓŁĆ GĘŚLĄ", "A").value());
        assertEquals("stop|", StopKey.of("Stop", null).value());
        assertEquals(StopKey.of("Stop", null), StopKey.of("Stop", ""));
        assertEquals(" stop | a ", StopKey.of(" Stop ", " A ").value());
    }
}
