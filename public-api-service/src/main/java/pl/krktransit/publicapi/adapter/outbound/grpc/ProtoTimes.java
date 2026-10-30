package pl.krktransit.publicapi.adapter.outbound.grpc;

import com.google.protobuf.Timestamp;
import java.time.Instant;

final class ProtoTimes {
    private ProtoTimes() {}

    static Instant instant(Timestamp timestamp) { return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()); }
}
