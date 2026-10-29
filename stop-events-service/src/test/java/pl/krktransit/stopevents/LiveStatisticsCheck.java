package pl.krktransit.stopevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import pl.krktransit.stopevents.contracts.v1.*;
import pl.krktransit.stopevents.support.LiveServiceContainer;
import pl.krktransit.stopevents.support.OwnedRedis;

final class LiveStatisticsCheck {
    static final String TOKEN = "disposable-statistics-token";
    static final int PORT = 9092;
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final LocalDate ESTIMATED_FROM = LocalDate.of(2026, 3, 19);
    private static final int OBSERVED = 1;
    private static final int RANKING = 10;
    private static final String PROBE_LINE = "LIVE1";
    private static final String CACHE = "krktransit:stop-events:statistics:v1:{results}";
    private static final String COUNT = "SELECT count(*) FROM stop_events.events";
    private static final String EVENTS = """
            SELECT source_id, trip_id, service_date, stop_sequence, max_stop_sequence, line_number, stop_name,
                   headsign, license_plate, planned_time, event_time, delay_seconds, detection_method
            FROM stop_events.events
            WHERE city_id = 'krakow' AND source_id IN ('mpk-bus', 'mobilis-bus', 'mpk-tram')
            """;
    private static final String PROBE = """
            INSERT INTO stop_events.events (service_date, city_id, source_id, trip_id, stop_sequence, stop_id,
                line_number, stop_name, direction_id, headsign, planned_time, event_time, license_plate,
                detection_method, is_estimated, static_hash, max_stop_sequence)
            VALUES (?, 'krakow', 'mpk-bus', ?, 2, 'krakow:mpk-bus:probe-stop', ?, 'Probe', 0, 'Probe', ?, ?,
                'PROBE', 1, false, 'probe', 5)
            """;
    private final DataSource database;
    private final OwnedRedis redis;
    private final LiveServiceContainer service;
    private final StringBuilder report = new StringBuilder();
    private final Map<Status.Code, Integer> statuses = new EnumMap<>(Status.Code.class);
    private final List<Long> latencies = new ArrayList<>();
    private StatisticsServiceGrpc.StatisticsServiceBlockingStub client;
    private int cases;

    LiveStatisticsCheck(DataSource database, OwnedRedis redis, LiveServiceContainer service) {
        this.database = database;
        this.redis = redis;
        this.service = service;
    }

    void verify(Path artifacts) throws IOException {
        var channel = ManagedChannelBuilder.forAddress(service.getHost(), service.getMappedPort(PORT)).usePlaintext().build();
        try {
            var events = stableEvents();
            client = StatisticsServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(bearer()));
            verifyAgainstOracle(events);
            report.append("Cached results after oracle pass: ").append(cacheEntries()).append('\n');
            verifyErrors(channel, events.getFirst().date());
            verifyCache();
            verifyLogs();
            report.append("Statistics live check passed\n");
        } finally {
            channel.shutdownNow();
            Files.createDirectories(artifacts);
            Files.writeString(artifacts.resolve("statistics.txt"), report);
            System.out.println(report);
        }
    }

    private List<Event> stableEvents() {
        long[] previous = {-1};
        int[] unchanged = {0};
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(5)).until(() -> {
            long current = count();
            unchanged[0] = current == previous[0] ? unchanged[0] + 1 : 0;
            previous[0] = current;
            return unchanged[0] >= 3;
        });
        var events = new ArrayList<Event>();
        try (var connection = database.getConnection(); var query = connection.prepareStatement(EVENTS);
             var rows = query.executeQuery()) {
            while (rows.next()) {
                events.add(new Event(rows.getString(1), rows.getString(2), rows.getObject(3, LocalDate.class),
                        rows.getInt(4), rows.getInt(5), rows.getString(6), rows.getString(7), rows.getString(8),
                        rows.getString(9), rows.getObject(10, OffsetDateTime.class).toInstant(),
                        rows.getObject(11, OffsetDateTime.class).toInstant(), rows.getInt(12), rows.getInt(13)));
            }
        } catch (SQLException failure) { throw new AssertionError(failure); }
        assertThat(events).isNotEmpty();
        report.append("Stable snapshot: ").append(events.size()).append(" events\n");
        return events;
    }

    private long count() throws SQLException {
        try (var connection = database.getConnection(); var query = connection.prepareStatement(COUNT);
             var rows = query.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void verifyAgainstOracle(List<Event> events) {
        var lines = busiestLines(events);
        var dates = events.stream().map(Event::date).distinct().sorted().toList();
        var today = LocalDate.now(WARSAW);
        var ranges = new ArrayList<List<LocalDate>>();
        ranges.add(List.of(dates.getFirst(), dates.getLast()));
        if (dates.size() > 1) dates.forEach(date -> ranges.add(List.of(date, date)));
        ranges.removeIf(range -> range.get(1).isAfter(today));
        report.append("Lines: ").append(lines).append("\nService dates: ").append(dates).append('\n');
        for (var line : lines) {
            for (var range : ranges) {
                for (boolean estimated : List.of(false, true)) compare(events, line, range.get(0), range.get(1), estimated);
            }
        }
        var sorted = latencies.stream().sorted().toList();
        report.append("Oracle cases: ").append(cases).append(", RPC statuses: ").append(statuses).append('\n');
        report.append("RPC latency ms: median=").append(sorted.get(sorted.size() / 2))
                .append(", p95=").append(sorted.get((int) (sorted.size() * 0.95)))
                .append(", max=").append(sorted.getLast()).append('\n');
    }

    private List<String> busiestLines(List<Event> events) {
        return events.stream().collect(Collectors.groupingBy(Event::source, Collectors.groupingBy(Event::line, Collectors.counting())))
                .values().stream().flatMap(lines -> lines.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(2).map(Map.Entry::getKey))
                .distinct().sorted().toList();
    }

    private void compare(List<Event> events, String line, LocalDate start, LocalDate end, boolean estimated) {
        var request = request(line, start, end, estimated);
        var label = line + " " + start + ".." + end + " estimated=" + estimated;
        var recorded = events.stream().filter(event -> event.line().equals(line)
                && !event.date().isBefore(start) && !event.date().isAfter(end)).toList();
        var maxDelay = call(stub -> stub.getMaxDelay(request));
        var routeDelay = call(stub -> stub.getRouteDelay(request));
        var punctuality = call(stub -> stub.getPunctuality(request));
        var trend = call(stub -> stub.getTrend(request));
        cases++;
        if (recorded.isEmpty()) {
            assertThat(List.of(maxDelay, routeDelay, punctuality, trend)).as(label).allMatch(Optional::isEmpty);
            return;
        }
        assertThat(List.of(maxDelay, routeDelay, punctuality, trend)).as(label).allMatch(Optional::isPresent);
        var filtered = recorded.stream().filter(event -> event.sequence() > 1 && event.sequence() < event.max()
                && (event.method() == OBSERVED || (estimated && !event.date().isBefore(ESTIMATED_FROM)))).toList();
        verifyMaxDelay(label, filtered, maxDelay.get());
        verifyRouteDelay(label, filtered, routeDelay.get());
        verifyPunctuality(label, filtered, punctuality.get());
        verifyTrend(label, filtered, trend.get());
        if (start.equals(events.stream().map(Event::date).min(Comparator.naturalOrder()).orElseThrow()) && !estimated) {
            sample(label, maxDelay.get(), routeDelay.get(), punctuality.get(), trend.get());
        }
    }

    private void verifyMaxDelay(String label, List<Event> filtered, MaxDelayResponse response) {
        var expected = new ArrayList<Segment>();
        for (var trip : byTrip(filtered)) {
            for (int i = 1; i < trip.size(); i++) {
                var from = trip.get(i - 1);
                var to = trip.get(i);
                if (to.sequence() != from.sequence() + 1 || !to.plate().equals(from.plate())) continue;
                expected.add(new Segment(to.trip(), to.date(), to.line(), to.plate(), from.stop(), to.stop(),
                        from.sequence(), to.sequence(), from.planned(), from.time(), to.planned(), to.time(),
                        to.delay() - from.delay(), to.headsign(), from.method() != OBSERVED || to.method() != OBSERVED));
            }
        }
        var actual = response.getMaxDelayList().stream().map(this::segment).toList();
        assertThat(response.getTripsAnalyzed()).as(label).isEqualTo(trips(expected, Segment::trip, Segment::date));
        assertThat(actual).as(label).hasSize(Math.min(RANKING, expected.size())).isSubsetOf(expected);
        assertThat(actual.stream().map(Segment::generated).toList()).as(label).isEqualTo(expected.stream()
                .map(Segment::generated).sorted(Comparator.reverseOrder()).limit(RANKING).toList());
    }

    private void verifyRouteDelay(String label, List<Event> filtered, RouteDelayResponse response) {
        var expected = new ArrayList<Route>();
        for (var trip : byTrip(filtered)) {
            if (trip.stream().map(Event::plate).distinct().count() != 1) continue;
            var first = trip.stream().filter(event -> event.sequence() == 2).findFirst();
            if (first.isEmpty()) continue;
            var last = trip.stream().filter(event -> event.sequence() == first.get().max() - 1).findFirst();
            if (last.isEmpty()) continue;
            var a = first.get();
            var b = last.get();
            expected.add(new Route(a.trip(), a.date(), a.line(), a.plate(), a.stop(), b.stop(), a.planned(), a.time(),
                    b.planned(), b.time(), a.delay(), b.delay(), b.delay() - a.delay(), a.headsign(),
                    a.method() != OBSERVED || b.method() != OBSERVED));
        }
        var actual = response.getMaxRouteDelayList().stream().map(this::route).toList();
        assertThat(response.getTripsAnalyzed()).as(label).isEqualTo(trips(expected, Route::trip, Route::date));
        assertThat(actual).as(label).hasSize(Math.min(RANKING, expected.size())).isSubsetOf(expected);
        assertThat(actual.stream().map(Route::generated).toList()).as(label).isEqualTo(expected.stream()
                .map(Route::generated).sorted(Comparator.reverseOrder()).limit(RANKING).toList());
    }

    private void verifyPunctuality(String label, List<Event> filtered, PunctualityResponse response) {
        long total = filtered.size();
        long onTime = filtered.stream().filter(event -> event.delay() <= 120).count();
        long slight = filtered.stream().filter(event -> event.delay() > 120 && event.delay() <= 360).count();
        long delayed = filtered.stream().filter(event -> event.delay() > 360).count();
        assertThat(List.of(response.getTotalStops(), response.getOnTimeCount(), response.getSlightlyDelayedCount(),
                response.getDelayedCount())).as(label).containsExactly(total, onTime, slight, delayed);
        if (total == 0) return;
        assertThat(response.getOnTimePercent()).as(label).isCloseTo(100.0 * onTime / total, within(0.051));
        assertThat(response.getSlightlyDelayedPercent()).as(label).isCloseTo(100.0 * slight / total, within(0.051));
        assertThat(response.getDelayedPercent()).as(label).isCloseTo(100.0 * delayed / total, within(0.051));
    }

    private void verifyTrend(String label, List<Event> filtered, TrendResponse response) {
        var expected = filtered.stream().collect(Collectors.groupingBy(Event::date, TreeMap::new, Collectors.toList()))
                .entrySet().stream().map(day -> new Day(day.getKey().toString(),
                        BigDecimal.valueOf(day.getValue().stream().mapToLong(Event::delay).sum())
                                .divide(BigDecimal.valueOf(day.getValue().size()), 1, RoundingMode.HALF_UP).doubleValue(),
                        day.getValue().stream().map(Event::trip).distinct().count())).toList();
        var actual = response.getDaysList().stream()
                .map(day -> new Day(day.getDate(), day.getAvgDelaySeconds(), day.getTripsCount())).toList();
        assertThat(actual).as(label).isEqualTo(expected);
    }

    private void sample(String label, MaxDelayResponse maxDelay, RouteDelayResponse routeDelay,
                        PunctualityResponse punctuality, TrendResponse trend) {
        report.append("\n").append(label).append('\n');
        report.append("  max-delay: trips=").append(maxDelay.getTripsAnalyzed()).append(", top=");
        maxDelay.getMaxDelayList().stream().limit(3).forEach(row -> report.append(row.getFromStop()).append(" -> ")
                .append(row.getToStop()).append(" +").append(row.getDelayGeneratedSeconds()).append("s [")
                .append(row.getVehicleNumber()).append("]; "));
        report.append("\n  route-delay: trips=").append(routeDelay.getTripsAnalyzed()).append(", top=");
        routeDelay.getMaxRouteDelayList().stream().limit(3).forEach(row -> report.append(row.getFirstStop())
                .append(" -> ").append(row.getLastStop()).append(" ").append(row.getStartDelaySeconds()).append("s->")
                .append(row.getEndDelaySeconds()).append("s; "));
        report.append("\n  punctuality: total=").append(punctuality.getTotalStops()).append(", on time=")
                .append(punctuality.getOnTimePercent()).append("%, slight=").append(punctuality.getSlightlyDelayedPercent())
                .append("%, delayed=").append(punctuality.getDelayedPercent()).append("%\n  trend:");
        trend.getDaysList().forEach(day -> report.append(' ').append(day.getDate()).append(" avg=")
                .append(day.getAvgDelaySeconds()).append("s trips=").append(day.getTripsCount()).append(';'));
        report.append("\n\n");
    }

    private void verifyErrors(ManagedChannel channel, LocalDate date) {
        var tomorrow = LocalDate.now(WARSAW).plusDays(1);
        expect(Status.Code.NOT_FOUND, () -> stub().getTrend(request("ZZ999", date, date, false)));
        expect(Status.Code.INVALID_ARGUMENT, () -> stub().getTrend(request("139", tomorrow, tomorrow, false)));
        expect(Status.Code.INVALID_ARGUMENT, () -> stub().getTrend(request("x' OR 1=1", date, date, false)));
        expect(Status.Code.INVALID_ARGUMENT, () -> stub().getTrend(StatisticsRequest.newBuilder().setLineNumber("139").build()));
        expect(Status.Code.UNAUTHENTICATED, () -> StatisticsServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(30, TimeUnit.SECONDS).getTrend(request("139", date, date, false)));
        report.append("Error statuses: NOT_FOUND, INVALID_ARGUMENT x3, UNAUTHENTICATED as expected\n");
    }

    private void verifyCache() {
        var date = LocalDate.now(WARSAW).minusDays(10);
        var request = request(PROBE_LINE, date, date, false);
        insertProbe(date, "cache-probe-1", 1000);
        long coldStarted = System.nanoTime();
        var first = stub().getPunctuality(request);
        long cold = (System.nanoTime() - coldStarted) / 1_000;
        assertThat(first.getTotalStops()).isEqualTo(1);
        long remaining = remainingTtlMillis("PUNCTUALITY:" + PROBE_LINE + ":" + date + ":" + date + ":false");
        assertThat(remaining).isBetween(20_000L, 30_000L);
        insertProbe(date, "cache-probe-2", 0);
        long warmStarted = System.nanoTime();
        var cached = stub().getPunctuality(request);
        long warm = (System.nanoTime() - warmStarted) / 1_000;
        assertThat(cached).isEqualTo(first);
        await().pollDelay(Duration.ofMillis(remaining)).atMost(Duration.ofMillis(remaining + 15_000))
                .pollInterval(Duration.ofSeconds(1)).until(() -> stub().getPunctuality(request).getTotalStops() == 2);
        var refreshed = stub().getPunctuality(request);
        assertThat(List.of(refreshed.getOnTimeCount(), refreshed.getDelayedCount())).containsExactly(1L, 1L);
        report.append("Cache probe: first SQL total=1 (").append(cold).append(" us), TTL remaining=").append(remaining)
                .append(" ms, after second insert still total=").append(cached.getTotalStops()).append(" (")
                .append(warm).append(" us), after TTL total=").append(refreshed.getTotalStops()).append('\n');
    }

    private void verifyLogs() {
        var logs = service.getLogs();
        assertThat(logs).doesNotContain("Redis rejected a statistics cache command")
                .doesNotContain("Statistics cache unavailable").doesNotContain("Statistics query failed");
    }

    private void insertProbe(LocalDate date, String trip, int delay) {
        var planned = date.atTime(12, 0).atZone(WARSAW).toOffsetDateTime();
        try (var connection = database.getConnection(); var insert = connection.prepareStatement(PROBE)) {
            insert.setObject(1, date);
            insert.setString(2, "krakow:mpk-bus:" + trip);
            insert.setString(3, PROBE_LINE);
            insert.setObject(4, planned);
            insert.setObject(5, planned.plusSeconds(delay));
            insert.executeUpdate();
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }

    private long cacheEntries() {
        var client = RedisClient.create(redis.ownedUrl());
        try (var connection = client.connect()) { return connection.sync().hlen(CACHE + ":values"); }
        finally { client.shutdown(); }
    }

    private long remainingTtlMillis(String member) {
        var client = RedisClient.create(redis.ownedUrl());
        try (var connection = client.connect()) {
            var expiry = connection.sync().zscore(CACHE + ":expiry", member);
            assertThat(expiry).as("cache entry " + member).isNotNull();
            var time = connection.sync().time();
            long now = Long.parseLong(time.getFirst()) * 1000 + Long.parseLong(time.get(1)) / 1000;
            return expiry.longValue() - now;
        } finally { client.shutdown(); }
    }

    private <T> Optional<T> call(Function<StatisticsServiceGrpc.StatisticsServiceBlockingStub, T> rpc) {
        long started = System.nanoTime();
        try {
            var response = rpc.apply(stub());
            statuses.merge(Status.Code.OK, 1, Integer::sum);
            return Optional.of(response);
        } catch (StatusRuntimeException failure) {
            statuses.merge(failure.getStatus().getCode(), 1, Integer::sum);
            assertThat(failure.getStatus().getCode()).as(failure.getStatus().toString()).isEqualTo(Status.Code.NOT_FOUND);
            return Optional.empty();
        } finally { latencies.add((System.nanoTime() - started) / 1_000_000); }
    }

    private void expect(Status.Code code, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                failure -> assertThat(failure.getStatus().getCode()).isEqualTo(code));
    }

    private StatisticsServiceGrpc.StatisticsServiceBlockingStub stub() { return client.withDeadlineAfter(30, TimeUnit.SECONDS); }

    private static Metadata bearer() {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + TOKEN);
        return headers;
    }

    private static StatisticsRequest request(String line, LocalDate start, LocalDate end, boolean estimated) {
        return StatisticsRequest.newBuilder().setLineNumber(line).setStartDate(start.toString())
                .setEndDate(end.toString()).setIncludeEstimated(estimated).build();
    }

    private static Collection<List<Event>> byTrip(List<Event> events) {
        return events.stream().sorted(Comparator.comparingInt(Event::sequence))
                .collect(Collectors.groupingBy(event -> event.trip() + "|" + event.date(), LinkedHashMap::new,
                        Collectors.toList())).values();
    }

    private static <T> long trips(List<T> rows, Function<T, String> trip, Function<T, LocalDate> date) {
        return rows.stream().map(row -> trip.apply(row) + "|" + date.apply(row)).distinct().count();
    }

    private Segment segment(SegmentDelay row) {
        return new Segment(row.getTripId(), LocalDate.parse(row.getServiceDate()), row.getLineNumber(),
                row.getVehicleNumber(), row.getFromStop(), row.getToStop(), row.getFromSequence(), row.getToSequence(),
                instant(row.getFromPlannedTime()), instant(row.getFromEventTime()), instant(row.getToPlannedTime()),
                instant(row.getToEventTime()), row.getDelayGeneratedSeconds(), row.getHeadsign(), row.getIsEstimated());
    }

    private Route route(RouteDelay row) {
        return new Route(row.getTripId(), LocalDate.parse(row.getServiceDate()), row.getLineNumber(),
                row.getVehicleNumber(), row.getFirstStop(), row.getLastStop(), instant(row.getFirstPlannedTime()),
                instant(row.getFirstEventTime()), instant(row.getLastPlannedTime()), instant(row.getLastEventTime()),
                row.getStartDelaySeconds(), row.getEndDelaySeconds(), row.getDelayGeneratedSeconds(), row.getHeadsign(),
                row.getIsEstimated());
    }

    private static Instant instant(Timestamp time) { return Instant.ofEpochSecond(time.getSeconds(), time.getNanos()); }

    private record Event(String source, String trip, LocalDate date, int sequence, int max, String line, String stop,
                         String headsign, String plate, Instant planned, Instant time, int delay, int method) {}

    private record Segment(String trip, LocalDate date, String line, String plate, String fromStop, String toStop,
                           int fromSequence, int toSequence, Instant fromPlanned, Instant fromTime, Instant toPlanned,
                           Instant toTime, int generated, String headsign, boolean estimated) {}

    private record Route(String trip, LocalDate date, String line, String plate, String firstStop, String lastStop,
                         Instant firstPlanned, Instant firstTime, Instant lastPlanned, Instant lastTime, int startDelay,
                         int endDelay, int generated, String headsign, boolean estimated) {}

    private record Day(String date, double average, long trips) {}
}
