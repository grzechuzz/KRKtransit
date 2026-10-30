package pl.krktransit.publicapi.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.krktransit.publicapi.support.FakeScheduleService.feedKey;
import static pl.krktransit.publicapi.support.FakeScheduleService.tripKey;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.VehiclePosition;
import pl.krktransit.livevehicles.contracts.v1.VehicleStopStatus;
import pl.krktransit.publicapi.bootstrap.PublicApiApplication;
import pl.krktransit.publicapi.bootstrap.PublicApiProperties;
import pl.krktransit.publicapi.support.FakeUpstreams;
import pl.krktransit.schedule.contracts.v1.GetShapeResponse;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.ShapePoint;
import pl.krktransit.schedule.contracts.v1.TripStop;
import pl.krktransit.stopevents.contracts.v1.MaxDelayResponse;
import pl.krktransit.stopevents.contracts.v1.PunctualityResponse;
import pl.krktransit.stopevents.contracts.v1.RouteDelay;
import pl.krktransit.stopevents.contracts.v1.RouteDelayResponse;
import pl.krktransit.stopevents.contracts.v1.SegmentDelay;
import pl.krktransit.stopevents.contracts.v1.TrendDay;
import pl.krktransit.stopevents.contracts.v1.TrendResponse;

@SpringBootTest(classes = PublicApiApplication.class, properties = {
        "public-api.statistics.address=in-process:public-api-http-test",
        "public-api.statistics.token=statistics-token",
        "public-api.schedule.address=in-process:public-api-http-test",
        "public-api.schedule.hash-refresh=1ms",
        "public-api.live-vehicles.address=in-process:public-api-http-test",
        "public-api.live-vehicles.snapshot-lifetime=1ms",
        "PUBLIC_API_SHAPES_PER_MINUTE=2"})
class PublicApiHttpTest {
    private static final FakeUpstreams UPSTREAMS = startUpstreams();
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private static final String STATISTICS_QUERY = "?start_date=2026-10-01&end_date=2026-10-07";
    private static final Instant PLANNED = Instant.parse("2026-10-01T12:00:00Z");
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PublicApiProperties properties;
    private MockMvc mvc;
    private String client;

    @BeforeEach
    void setUp() {
        UPSTREAMS.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = "198.51.100." + CLIENTS.incrementAndGet();
    }

    @AfterAll
    static void stopUpstreams() { UPSTREAMS.close(); }

    @Test
    void maxDelayKeepsThePythonContractAndForwardsTheQuery() throws Exception {
        UPSTREAMS.statistics.maxDelay = MaxDelayResponse.newBuilder().setTripsAnalyzed(42).addMaxDelay(SegmentDelay.newBuilder()
                .setTripId("krakow:mpk-bus:1").setServiceDate("2026-10-01").setLineNumber("194").setVehicleNumber("DN041")
                .setFromStop("Rondo").setToStop("Plac").setFromSequence(2).setToSequence(3)
                .setFromPlannedTime(timestamp(PLANNED)).setFromEventTime(timestamp(PLANNED.plusSeconds(61).plusMillis(250)))
                .setToPlannedTime(timestamp(PLANNED.plusSeconds(120))).setToEventTime(timestamp(PLANNED.plusSeconds(300)))
                .setDelayGeneratedSeconds(179).setHeadsign("Krowodrza").setIsEstimated(true)).build();

        call(get("/v1/lines/194/stats/max-delay" + STATISTICS_QUERY + "&include_estimated=true")).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"line_number": "194", "start_date": "2026-10-01", "end_date": "2026-10-07", "trips_analyzed": 42,
                         "max_delay": [{"trip_id": "krakow:mpk-bus:1", "line_number": "194", "vehicle_number": "DN041",
                           "from_stop": "Rondo", "to_stop": "Plac", "from_sequence": 2, "to_sequence": 3,
                           "from_planned_time": "2026-10-01 14:00:00", "from_event_time": "2026-10-01 14:01:01.250000",
                           "to_planned_time": "2026-10-01 14:02:00", "to_event_time": "2026-10-01 14:05:00",
                           "delay_generated_seconds": 179, "headsign": "Krowodrza", "service_date": "2026-10-01",
                           "is_estimated": true}]}""", JsonCompareMode.STRICT));
        var request = UPSTREAMS.statistics.lastRequest;
        assertThat(request.getLineNumber()).isEqualTo("194");
        assertThat(request.getStartDate()).isEqualTo("2026-10-01");
        assertThat(request.getEndDate()).isEqualTo("2026-10-07");
        assertThat(request.getIncludeEstimated()).isTrue();
        assertThat(UPSTREAMS.authorizations).containsExactly("Bearer statistics-token");
    }

    @Test
    void routeDelayPunctualityAndTrendKeepThePythonContract() throws Exception {
        UPSTREAMS.statistics.routeDelay = RouteDelayResponse.newBuilder().setTripsAnalyzed(3).addMaxRouteDelay(RouteDelay
                .newBuilder().setTripId("krakow:mpk-tram:9").setServiceDate("2026-10-02").setLineNumber("4")
                .setVehicleNumber("RP001").setFirstStop("A").setLastStop("Z").setFirstPlannedTime(timestamp(PLANNED))
                .setFirstEventTime(timestamp(PLANNED.plusSeconds(30))).setLastPlannedTime(timestamp(PLANNED.plusSeconds(3600)))
                .setLastEventTime(timestamp(PLANNED.plusSeconds(4000))).setStartDelaySeconds(30).setEndDelaySeconds(400)
                .setDelayGeneratedSeconds(370).setHeadsign("Bronowice").setIsEstimated(false)).build();
        UPSTREAMS.statistics.punctuality = PunctualityResponse.newBuilder().setTotalStops(10).setOnTimeCount(7)
                .setOnTimePercent(70.0).setSlightlyDelayedCount(2).setSlightlyDelayedPercent(20.0).setDelayedCount(1)
                .setDelayedPercent(10.0).build();
        UPSTREAMS.statistics.trend = TrendResponse.newBuilder().addDays(TrendDay.newBuilder().setDate("2026-10-01")
                .setAvgDelaySeconds(81.5).setTripsCount(12)).build();

        call(get("/v1/lines/4/stats/route-delay" + STATISTICS_QUERY)).andExpect(status().isOk()).andExpect(content().json("""
                {"line_number": "4", "start_date": "2026-10-01", "end_date": "2026-10-07", "trips_analyzed": 3,
                 "max_route_delay": [{"trip_id": "krakow:mpk-tram:9", "line_number": "4", "vehicle_number": "RP001",
                   "first_stop": "A", "last_stop": "Z", "first_planned_time": "2026-10-01 14:00:00",
                   "first_event_time": "2026-10-01 14:00:30", "last_planned_time": "2026-10-01 15:00:00",
                   "last_event_time": "2026-10-01 15:06:40", "start_delay_seconds": 30, "end_delay_seconds": 400,
                   "delay_generated_seconds": 370, "headsign": "Bronowice", "service_date": "2026-10-02",
                   "is_estimated": false}]}""", JsonCompareMode.STRICT));
        assertThat(UPSTREAMS.statistics.lastRequest.getIncludeEstimated()).isFalse();
        call(get("/v1/lines/4/stats/punctuality" + STATISTICS_QUERY)).andExpect(status().isOk()).andExpect(content().json("""
                {"line_number": "4", "start_date": "2026-10-01", "end_date": "2026-10-07", "total_stops": 10,
                 "on_time_count": 7, "on_time_percent": 70.0, "slightly_delayed_count": 2,
                 "slightly_delayed_percent": 20.0, "delayed_count": 1, "delayed_percent": 10.0}""", JsonCompareMode.STRICT));
        call(get("/v1/lines/4/stats/trend" + STATISTICS_QUERY)).andExpect(status().isOk()).andExpect(content().json("""
                {"line_number": "4", "start_date": "2026-10-01", "end_date": "2026-10-07",
                 "days": [{"date": "2026-10-01", "avg_delay_seconds": 81.5, "trips_count": 12}]}""", JsonCompareMode.STRICT));
    }

    @Test
    void statisticsFailuresUseThePythonErrorContract() throws Exception {
        UPSTREAMS.statistics.failure = Status.NOT_FOUND;
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY)).andExpect(status().isNotFound()).andExpect(content().json("""
                {"error_code": "RESOURCE_NOT_FOUND", "message": "Line '194' not found"}""", JsonCompareMode.STRICT));

        UPSTREAMS.statistics.failure = Status.INVALID_ARGUMENT.withDescription("start_date must be <= end_date");
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY)).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"error_code": "VALIDATION_ERROR", "message": "start_date must be <= end_date"}""",
                        JsonCompareMode.STRICT));

        UPSTREAMS.statistics.failure = Status.UNAVAILABLE;
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY)).andExpect(status().isBadGateway()).andExpect(content().json("""
                {"error_code": "EXTERNAL_SERVICE_ERROR", "message": "Statistics service is unavailable"}""",
                JsonCompareMode.STRICT));

        UPSTREAMS.statistics.failure = Status.RESOURCE_EXHAUSTED;
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY)).andExpect(status().isServiceUnavailable());

        UPSTREAMS.statistics.failure = Status.UNAUTHENTICATED;
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY)).andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"error_code": "INTERNAL_ERROR", "message": "Internal server error"}""", JsonCompareMode.STRICT));
    }

    @Test
    void invalidParametersAreRejectedInTheFastApiShapeWithoutCallingUpstream() throws Exception {
        call(get("/v1/lines/123456/stats/trend" + STATISTICS_QUERY)).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"detail": [{"type": "string_too_long", "loc": ["path", "line_number"],
                          "msg": "String should have at most 5 characters", "input": "123456"}]}""", JsonCompareMode.STRICT));
        call(get("/v1/lines/1-2/stats/trend" + STATISTICS_QUERY)).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"detail": [{"type": "string_pattern_mismatch", "loc": ["path", "line_number"],
                          "msg": "String should match pattern '^[a-zA-Z0-9]{1,5}$'", "input": "1-2"}]}""",
                        JsonCompareMode.STRICT));
        call(get("/v1/lines/194/stats/trend")).andExpect(status().isUnprocessableContent()).andExpect(content().json("""
                {"detail": [{"type": "missing", "loc": ["query", "end_date"], "msg": "Field required", "input": null},
                            {"type": "missing", "loc": ["query", "start_date"], "msg": "Field required", "input": null}]}""",
                JsonCompareMode.STRICT));
        call(get("/v1/lines/194/stats/trend?start_date=2026-13-01&end_date=2026-10-07")).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"detail": [{"type": "date_parsing", "loc": ["query", "start_date"],
                          "msg": "Input should be a valid date", "input": "2026-13-01"}]}""", JsonCompareMode.STRICT));
        call(get("/v1/lines/194/stats/trend" + STATISTICS_QUERY + "&include_estimated=maybe"))
                .andExpect(status().isUnprocessableContent()).andExpect(content().json("""
                        {"detail": [{"type": "bool_parsing", "loc": ["query", "include_estimated"],
                          "msg": "Input should be a valid boolean, unable to interpret input", "input": "maybe"}]}""",
                        JsonCompareMode.STRICT));
        call(get("/v1/trips/" + "a".repeat(129) + "/stops")).andExpect(status().isUnprocessableContent());
        call(get("/v1/shapes/bad!id")).andExpect(status().isUnprocessableContent());
        assertThat(UPSTREAMS.statistics.lastRequest).isNull();
        assertThat(UPSTREAMS.authorizations).isEmpty();
    }

    @Test
    void vehiclesAreEnrichedWithTheirCurrentTrips() throws Exception {
        UPSTREAMS.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "bus-hash");
        UPSTREAMS.schedule.trips.put(tripKey("bus-hash", "krakow:mpk-bus:vehicles-1"), GetTripContextResponse.newBuilder()
                .setLineNumber("194").setShapeId("krakow:mpk-bus:sh1").build());
        UPSTREAMS.liveVehicles.positions = ListVehiclePositionsResponse.newBuilder()
                .addVehicles(vehicle("krakow:mpk-bus:vehicles-1", "DN041").setBearing(90.5))
                .addVehicles(vehicle("krakow:mpk-bus:vehicles-unknown", "DN002")).build();

        call(get("/v1/vehicles/positions")).andExpect(status().isOk()).andExpect(content().json("""
                {"count": 1, "vehicles": [{"trip_id": "krakow:mpk-bus:vehicles-1", "license_plate": "DN041",
                  "line_number": "194", "headsign": "", "shape_id": "krakow:mpk-bus:sh1", "latitude": 50.06,
                  "longitude": 19.94, "bearing": 90.5, "timestamp": "2026-10-30T10:00:00+00:00"}]}""", JsonCompareMode.STRICT));
    }

    @Test
    void tripStopsAndShapesComeFromTheCurrentScheduleVersion() throws Exception {
        UPSTREAMS.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "hash");
        UPSTREAMS.schedule.trips.put(tripKey("hash", "krakow:mpk-bus:1"), GetTripContextResponse.newBuilder()
                .setTripId("krakow:mpk-bus:1").addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s1")
                        .setStopName("Rondo").setStopDesc("01").setLatitude(50.1).setLongitude(19.9).setStopSequence(1))
                .addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s2").setStopName("Plac").setStopSequence(2)).build());
        UPSTREAMS.schedule.shapes.put(tripKey("hash", "krakow:mpk-bus:sh1"), GetShapeResponse.newBuilder()
                .setShapeId("krakow:mpk-bus:sh1").addPoints(ShapePoint.newBuilder().setLatitude(50.1).setLongitude(19.9)
                        .setSequence(1)).build());

        call(get("/v1/trips/krakow:mpk-bus:1/stops")).andExpect(status().isOk()).andExpect(content().json("""
                {"trip_id": "krakow:mpk-bus:1", "stops": [
                  {"stop_id": "krakow:mpk-bus:s1", "stop_name": "Rondo", "stop_desc": "01", "latitude": 50.1,
                   "longitude": 19.9, "sequence": 1},
                  {"stop_id": "krakow:mpk-bus:s2", "stop_name": "Plac", "stop_desc": null, "latitude": null,
                   "longitude": null, "sequence": 2}]}""", JsonCompareMode.STRICT));
        call(get("/v1/shapes/krakow:mpk-bus:sh1")).andExpect(status().isOk()).andExpect(content().json("""
                {"shape_id": "krakow:mpk-bus:sh1", "points": [{"latitude": 50.1, "longitude": 19.9, "sequence": 1}]}""",
                JsonCompareMode.STRICT));
        call(get("/v1/trips/krakow:mpk-bus:missing/stops")).andExpect(status().isNotFound()).andExpect(content().json("""
                {"error_code": "RESOURCE_NOT_FOUND", "message": "Trip 'krakow:mpk-bus:missing' not found"}""",
                JsonCompareMode.STRICT));
        call(get("/v1/trips/12345/stops")).andExpect(status().isNotFound());
    }

    @Test
    void upcomingStopsCombineVehicleProgressWithTheCurrentSchedule() throws Exception {
        UPSTREAMS.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "hash");
        UPSTREAMS.schedule.trips.put(tripKey("hash", "krakow:mpk-bus:up-1"), GetTripContextResponse.newBuilder()
                .setTripId("krakow:mpk-bus:up-1")
                .addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s1").setStopName("Rondo").setStopSequence(1)
                        .setArrivalSeconds(39_600).setDepartureSeconds(39_600))
                .addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s2").setStopName("Plac").setStopDesc("02")
                        .setStopSequence(2).setArrivalSeconds(39_780).setDepartureSeconds(39_800))
                .addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s3").setStopName("Dworzec").setStopSequence(3)
                        .setArrivalSeconds(88_500).setDepartureSeconds(88_500)).build());
        UPSTREAMS.liveVehicles.positions = ListVehiclePositionsResponse.newBuilder()
                .addVehicles(vehicle("krakow:mpk-bus:up-1", "DN041").setStopId("krakow:mpk-bus:s2").setCurrentStopSequence(1)
                        .setCurrentStatus(VehicleStopStatus.VEHICLE_STOP_STATUS_IN_TRANSIT_TO))
                .addVehicles(vehicle("krakow:mpk-bus:up-2", "DN042").setCurrentStopSequence(1)).build();

        call(get("/v1/trips/krakow:mpk-bus:up-1/upcoming-stops")).andExpect(status().isOk()).andExpect(content().json("""
                {"trip_id": "krakow:mpk-bus:up-1", "status": "IN_PROGRESS", "observed_at": "2026-10-30T10:00:00+00:00",
                 "stops": [
                   {"stop_id": "krakow:mpk-bus:s2", "stop_name": "Plac", "stop_desc": "02", "sequence": 2,
                    "scheduled_arrival": "2026-10-30T11:03:00+01:00", "predicted_arrival": null},
                   {"stop_id": "krakow:mpk-bus:s3", "stop_name": "Dworzec", "stop_desc": null, "sequence": 3,
                    "scheduled_arrival": "2026-10-31T00:35:00+01:00", "predicted_arrival": null}]}""",
                JsonCompareMode.STRICT));
        call(get("/v1/trips/krakow:mpk-bus:other/upcoming-stops")).andExpect(status().isNotFound()).andExpect(content().json("""
                {"error_code": "RESOURCE_NOT_FOUND", "message": "Vehicle on trip 'krakow:mpk-bus:other' not found"}""",
                JsonCompareMode.STRICT));
        call(get("/v1/trips/bad%20id/upcoming-stops")).andExpect(status().isUnprocessableContent());

        UPSTREAMS.schedule.failure = Status.UNAVAILABLE;
        call(get("/v1/trips/krakow:mpk-bus:up-1/upcoming-stops")).andExpect(status().isOk());
        call(get("/v1/trips/krakow:mpk-bus:up-2/upcoming-stops")).andExpect(status().isBadGateway());
    }

    @Test
    void rateLimitsCountEachClientAndRouteSeparately() throws Exception {
        UPSTREAMS.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "hash");

        call(get("/v1/shapes/krakow:mpk-bus:a")).andExpect(status().isNotFound());
        call(get("/v1/shapes/krakow:mpk-bus:b")).andExpect(status().isNotFound());
        call(get("/v1/shapes/krakow:mpk-bus:c")).andExpect(status().isTooManyRequests()).andExpect(content().json("""
                {"error_code": "RATE_LIMIT_EXCEEDED", "message": "Rate limit exceeded"}""", JsonCompareMode.STRICT));
        call(get("/v1/trips/krakow:mpk-bus:a/stops")).andExpect(status().isNotFound());
        mvc.perform(withClient(get("/v1/shapes/krakow:mpk-bus:a"), "203.0.113.1")).andExpect(status().isNotFound());
        assertThat(properties.rateLimits().perMinuteByRoute().keySet()).isSubsetOf(context
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet()
                .stream().flatMap(mapping -> mapping.getPatternValues().stream()).toList());
    }

    @Test
    void healthCorsUnknownRoutesAndDisabledDocumentation() throws Exception {
        call(get("/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\": \"healthy\"}", JsonCompareMode.STRICT));
        call(get("/health").header("Origin", "https://krktransit.pl"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://krktransit.pl"));
        call(get("/v1/unknown")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"detail\": \"Not Found\"}", JsonCompareMode.STRICT));
        call(post("/health")).andExpect(status().isMethodNotAllowed())
                .andExpect(content().json("{\"detail\": \"Method Not Allowed\"}", JsonCompareMode.STRICT));
        call(get("/openapi.json")).andExpect(status().isNotFound());
        call(get("/docs")).andExpect(status().isNotFound());
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(withClient(request, client));
    }

    private static MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder request, String address) {
        return request.with(servletRequest -> {
            servletRequest.setRemoteAddr(address);
            return servletRequest;
        });
    }

    private static VehiclePosition.Builder vehicle(String tripId, String plate) {
        return VehiclePosition.newBuilder().setCityId("krakow").setSourceId("mpk-bus").setTripId(tripId)
                .setLicensePlate(plate).setLatitude(50.06).setLongitude(19.94)
                .setTimestamp(timestamp(Instant.parse("2026-10-30T10:00:00Z")));
    }

    private static Timestamp timestamp(Instant time) {
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build();
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("public-api-http-test");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
