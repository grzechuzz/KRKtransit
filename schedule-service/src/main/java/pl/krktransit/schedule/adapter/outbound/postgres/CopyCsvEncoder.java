package pl.krktransit.schedule.adapter.outbound.postgres;

import java.nio.charset.StandardCharsets;

final class CopyCsvEncoder {
    private CopyCsvEncoder() {}

    static byte[] encode(Object... values) {
        var csv = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            appendValue(csv, values[index]);
        }
        return csv.append('\n').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendValue(StringBuilder csv, Object value) {
        if (value == null || value.toString().isEmpty()) {
            return;
        }
        csv.append('"');
        csv.append(value.toString().replace("\"", "\"\""));
        csv.append('"');
    }
}
