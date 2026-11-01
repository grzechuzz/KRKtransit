package pl.krktransit.stopevents.domain.event;

import java.util.Arrays;

public enum DetectionMethod {
    STOPPED_AT(1), SEQ_JUMP(2), TIMEOUT(3);
    private final int code;
    DetectionMethod(int code) { this.code = code; }
    public int code() { return code; }

    public static DetectionMethod fromCode(int code) {
        return Arrays.stream(values()).filter(method -> method.code == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown detection method " + code));
    }
}
