package pl.krktransit.stopevents.domain.event;

public enum DetectionMethod {
    STOPPED_AT(1), SEQ_JUMP(2), TIMEOUT(3);
    private final int code;
    DetectionMethod(int code) { this.code = code; }
    public int code() { return code; }
}
