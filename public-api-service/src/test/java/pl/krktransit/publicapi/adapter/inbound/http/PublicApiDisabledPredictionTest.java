package pl.krktransit.publicapi.adapter.inbound.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.krktransit.publicapi.support.FakeScheduleService.feedKey;
import static pl.krktransit.publicapi.support.FakeScheduleService.tripKey;

import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import pl.krktransit.livevehicles.contracts.v1.ListVehiclePositionsResponse;
import pl.krktransit.livevehicles.contracts.v1.VehiclePosition;
import pl.krktransit.publicapi.application.upcoming.PredictionSource;
import pl.krktransit.publicapi.bootstrap.PublicApiApplication;
import pl.krktransit.publicapi.support.FakeUpstreams;
import pl.krktransit.schedule.contracts.v1.GetTripContextResponse;
import pl.krktransit.schedule.contracts.v1.TripStop;

@SpringBootTest(classes = PublicApiApplication.class, properties = {
        "public-api.statistics.address=in-process:public-api-disabled-prediction-test",
        "public-api.schedule.address=in-process:public-api-disabled-prediction-test",
        "public-api.live-vehicles.address=in-process:public-api-disabled-prediction-test",
        "public-api.prediction.address="})
class PublicApiDisabledPredictionTest {
    private static final FakeUpstreams UPSTREAMS = startUpstreams();
    @Autowired
    private WebApplicationContext context;

    @AfterAll
    static void stopUpstreams() { UPSTREAMS.close(); }

    @Test
    void anEmptyAddressDisablesPredictionsWithoutCallingTheService() throws Exception {
        UPSTREAMS.schedule.hashes.put(feedKey("krakow", "mpk-bus"), "hash");
        UPSTREAMS.schedule.trips.put(tripKey("hash", "krakow:mpk-bus:t"), GetTripContextResponse.newBuilder()
                .setTripId("krakow:mpk-bus:t").setLineNumber("139").addStops(TripStop.newBuilder().setStopId("krakow:mpk-bus:s")
                        .setStopName("Rondo").setStopSequence(1).setArrivalSeconds(39_600).setDepartureSeconds(39_600)).build());
        UPSTREAMS.liveVehicles.positions = ListVehiclePositionsResponse.newBuilder().addVehicles(VehiclePosition.newBuilder()
                .setCityId("krakow").setSourceId("mpk-bus").setTripId("krakow:mpk-bus:t").setLicensePlate("DN041")
                .setLatitude(50).setLongitude(19.9).setCurrentStopSequence(1)
                .setTimestamp(Timestamp.newBuilder().setSeconds(Instant.parse("2026-10-30T10:20:00Z").getEpochSecond()))).build();

        MockMvcBuilders.webAppContextSetup(context).build().perform(get("/v1/trips/krakow:mpk-bus:t/upcoming-stops")
                .with(request -> { request.setRemoteAddr("198.51.100.200"); return request; })).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"trip_id": "krakow:mpk-bus:t", "status": "IN_PROGRESS", "observed_at": "2026-10-30T10:20:00+00:00",
                         "stops": [{"stop_id": "krakow:mpk-bus:s", "stop_name": "Rondo", "stop_desc": null, "sequence": 1,
                           "scheduled_arrival": "2026-10-30T11:00:00+01:00", "predicted_arrival": null}]}""",
                        JsonCompareMode.STRICT));

        assertThat(context.getBean(PredictionSource.class)).isSameAs(PredictionSource.DISABLED);
        assertThat(UPSTREAMS.prediction.requests).isEmpty();
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("public-api-disabled-prediction-test");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
