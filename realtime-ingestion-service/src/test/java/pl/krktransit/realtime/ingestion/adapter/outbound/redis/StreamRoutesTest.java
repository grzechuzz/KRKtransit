package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.krktransit.realtime.ingestion.support.Endpoints;

class StreamRoutesTest {
    private static final String PREFIX = "krktransit:realtime:observations:v1";

    @Test
    void routesEachSourceToItsOwnStreamWithItsLengthCap() {
        var routes = new StreamRoutes(PREFIX, Map.of(Endpoints.MPK_BUS, 150_000L, Endpoints.MOBILIS_BUS, 40_000L));

        var mobilis = routes.target(Endpoints.MOBILIS_BUS);
        assertThat(new String(mobilis.key(), StandardCharsets.UTF_8))
                .isEqualTo("krktransit:realtime:observations:v1:krakow:mobilis-bus");
        assertThat(mobilis.maxLength()).isEqualTo(40_000L);
        assertThat(new String(routes.target(Endpoints.MPK_BUS).key(), StandardCharsets.UTF_8))
                .isEqualTo("krktransit:realtime:observations:v1:krakow:mpk-bus");
    }

    @Test
    void rejectsBlankPrefixNonPositiveCapsAndUnknownSources() {
        assertThatThrownBy(() -> new StreamRoutes(" ", Map.of(Endpoints.MPK_BUS, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamRoutes(PREFIX, Map.of(Endpoints.MPK_BUS, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamRoutes(PREFIX, Map.of(Endpoints.MPK_BUS, 1L)).target(Endpoints.MPK_TRAM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
