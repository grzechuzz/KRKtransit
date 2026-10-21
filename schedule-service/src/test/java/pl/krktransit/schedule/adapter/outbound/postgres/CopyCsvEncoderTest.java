package pl.krktransit.schedule.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CopyCsvEncoderTest {
    @Test
    void preservesNullSemanticsAndEmbeddedCsvCharacters() {
        var bytes = CopyCsvEncoder.encode(42L, null, "", "a,\"b\"\nc", "Łagiewniki", " ");

        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("\"42\",,,\"a,\"\"b\"\"\nc\",\"Łagiewniki\",\" \"\n");
    }
}
