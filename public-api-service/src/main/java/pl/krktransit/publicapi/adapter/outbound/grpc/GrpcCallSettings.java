package pl.krktransit.publicapi.adapter.outbound.grpc;

import java.time.Duration;

public record GrpcCallSettings(String token, Duration deadline) {
    public GrpcCallSettings {
        if (deadline == null || !deadline.isPositive()) throw new IllegalArgumentException("A positive deadline is required");
    }

    @Override
    public String toString() { return "GrpcCallSettings[deadline=" + deadline + "]"; }
}
