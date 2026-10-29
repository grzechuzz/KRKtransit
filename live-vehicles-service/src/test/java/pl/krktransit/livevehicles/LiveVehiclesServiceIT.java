package pl.krktransit.livevehicles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.livevehicles.adapter.inbound.stream.StreamConsumer;
import pl.krktransit.livevehicles.bootstrap.LiveVehiclesApplication;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsRequest;
import pl.krktransit.livevehicles.contracts.v1.LiveVehiclesServiceGrpc;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.support.OwnedRedis;
import pl.krktransit.realtime.contracts.v1.Observation;
import pl.krktransit.realtime.contracts.v1.Position;
import pl.krktransit.realtime.contracts.v1.Source;
import pl.krktransit.realtime.contracts.v1.VehiclePosition;

@Testcontainers
class LiveVehiclesServiceIT {
    private static final String TOKEN = "owned-test-token";
    private static final List<SourceId> SOURCES = List.of(new SourceId("krakow", "mpk-bus"),
            new SourceId("krakow", "mobilis-bus"), new SourceId("krakow", "mpk-tram"));
    @Container
    private static final OwnedRedis REDIS = new OwnedRedis();

    @Test
    void realSpringServiceReadsAllSourcesThroughReadOnlyAclAndRebuildsAfterRestart() {
        String prefix = "krktransit:live-test:" + UUID.randomUUID();
        String user = "live-" + UUID.randomUUID();
        var publisher = RedisClient.create(REDIS.ownedUrl());
        try {
            try (var admin = publisher.connect()) {
                configureReader(admin.sync(), user, "on", ">" + TOKEN, "resetkeys", "%R~" + prefix + ":*", "resetchannels", "-@all",
                        "+xread", "+time", "+hello", "+client|setinfo", "+client|setname");
            }
            try (var connection = publisher.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))) {
                var now = Instant.now();
                SOURCES.forEach(source -> {
                    connection.sync().xadd(prefix + ":" + source, Map.of("payload", new byte[]{-1}));
                    connection.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "expired", now.minus(Duration.ofHours(2)), 50)));
                    connection.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "current", now, 0)));
                    connection.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "current", now.minusSeconds(2), 51)));
                });
            }
            try (var context = application(prefix, user)) { verifyPositions(context); }
            try (var context = application(prefix, user)) { verifyPositions(context); }
        } finally { publisher.shutdown(); }
    }

    @Test
    void independentReadersAdvanceAcrossMalformedEntriesAndUseRedisTimeForRecentCursors() {
        String prefix = "krktransit:live-test:" + UUID.randomUUID();
        var client = RedisClient.create(REDIS.ownedUrl());
        try (var connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
             var first = new StreamConsumer(() -> client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)),
                     prefix, Set.copyOf(SOURCES), 500, Duration.ofMillis(10));
             var second = new StreamConsumer(() -> client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)),
                     prefix, Set.copyOf(SOURCES), 500, Duration.ofMillis(10))) {
            var cursors = first.recentCursors(Set.copyOf(SOURCES), Duration.ofSeconds(30));
            assertThat(cursors.values()).allSatisfy(cursor -> {
                long age = System.currentTimeMillis() - Long.parseLong(cursor.split("-")[0]);
                assertThat(age).isBetween(29000L, 32000L);
                assertThat(cursor).endsWith("-0");
            });
            var ends = new HashMap<SourceId, String>();
            for (var source : SOURCES) {
                connection.sync().xadd(prefix + ":" + source, Map.of("payload", observation(source, "current", Instant.now(), 50)));
                ends.put(source, connection.sync().xadd(prefix + ":" + source, Map.of("payload", new byte[]{-1})));
            }
            var read = first.read(cursors);
            assertThat(read.positions()).hasSize(3);
            assertThat(read.cursors()).isEqualTo(ends);
            assertThat(second.read(cursors)).isEqualTo(read);
            assertThat(first.read(read.cursors()).positions()).isEmpty();
        } finally { client.shutdown(); }
    }

    private ConfigurableApplicationContext application(String prefix, String user) {
        return new SpringApplicationBuilder(LiveVehiclesApplication.class).bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(
                "--spring.grpc.server.port=0", "--live-vehicles.stream-prefix=" + prefix,
                "--live-vehicles.redis.url=" + REDIS.ownedUrl(), "--live-vehicles.redis.username=" + user,
                "--live-vehicles.redis.password=" + TOKEN, "--live-vehicles.grpc-token=" + TOKEN,
                "--live-vehicles.read-block=50ms");
    }

    private void configureReader(io.lettuce.core.api.sync.RedisCommands<String, String> commands, String user, String... rules) {
        var arguments = new io.lettuce.core.protocol.CommandArgs<>(StringCodec.UTF8).add("SETUSER").add(user);
        for (var rule : rules) arguments.add(rule);
        commands.dispatch(io.lettuce.core.protocol.CommandType.ACL,
                new io.lettuce.core.output.StatusOutput<>(StringCodec.UTF8), arguments);
    }

    private void verifyPositions(ConfigurableApplicationContext context) {
        int port = context.getBean(GrpcServerLifecycle.class).getPort();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        try {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TOKEN);
            var stub = LiveVehiclesServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var response = stub.withDeadlineAfter(2, TimeUnit.SECONDS).listVehiclePositions(ListVehiclePositionsRequest.getDefaultInstance());
                assertThat(response.getVehiclesList()).hasSize(3).allSatisfy(position -> {
                    assertThat(position.getLicensePlate()).isEqualTo("current");
                    assertThat(position.getLatitude()).isZero();
                    assertThat(position.getTripId()).endsWith(":trip");
                });
                assertThat(response.getVehiclesList()).extracting(pl.krktransit.livevehicles.contracts.v1.VehiclePosition::getSourceId)
                        .containsExactly("mobilis-bus", "mpk-bus", "mpk-tram");
            });
        } finally { channel.shutdownNow(); }
    }

    private byte[] observation(SourceId source, String plate, Instant instant, float latitude) {
        return Observation.newBuilder().setSource(Source.newBuilder().setCityId(source.cityId()).setSourceId(source.sourceId()))
                .setFetchedAt(Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()))
                .setVehiclePosition(VehiclePosition.newBuilder().setTripId("trip").setLicensePlate(plate).setTimestamp(instant.getEpochSecond())
                        .setPosition(Position.newBuilder().setLatitude(latitude).setLongitude(0))).build().toByteArray();
    }
}
