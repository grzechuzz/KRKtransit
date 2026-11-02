package pl.krktransit.publicapi.adapter.outbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.krktransit.prediction.contracts.v1.PredictArrivalsResponse;
import pl.krktransit.prediction.contracts.v1.PredictedArrival;
import pl.krktransit.publicapi.application.schedule.TripStop;
import pl.krktransit.publicapi.application.upcoming.PredictedDelays;
import pl.krktransit.publicapi.application.upcoming.PredictionRequest;
import pl.krktransit.publicapi.support.FakeUpstreams;

class GrpcPredictionSourceTest {
    private static final PredictionRequest REQUEST = new PredictionRequest("krakow", "krakow:mpk-bus:t", LocalDate.of(2026, 11, 2),
            "139", List.of(new TripStop("krakow:mpk-bus:a", "Rondo", "01", 50.0, 19.0, 1, 39_600, 39_620),
                    new TripStop("krakow:mpk-bus:b", "Plac", null, null, null, 2, 88_500, 88_500)), List.of(2));
    private final FakeUpstreams upstreams = startUpstreams();
    private final ManagedChannel channel = upstreams.channel();
    private final GrpcPredictionSource source =
            new GrpcPredictionSource(channel, new GrpcCallSettings("prediction-token", Duration.ofMillis(300)));

    @AfterEach
    void close() {
        channel.shutdownNow();
        upstreams.close();
    }

    @Test
    void sendsTheTripWithItsScheduleAndTheDisplayedRows() {
        source.predict(REQUEST);

        var request = upstreams.prediction.requests.getFirst();
        assertThat(request.getCityId()).isEqualTo("krakow");
        assertThat(request.getTripId()).isEqualTo("krakow:mpk-bus:t");
        assertThat(request.getServiceDate()).isEqualTo("2026-11-02");
        assertThat(request.getLineNumber()).isEqualTo("139");
        assertThat(request.getMaxStopSequence()).isEqualTo(2);
        assertThat(request.getTargetSequencesList()).containsExactly(2);
        assertThat(request.getStops(0).getStopName()).isEqualTo("Rondo");
        assertThat(request.getStops(0).getStopDesc()).isEqualTo("01");
        assertThat(request.getStops(0).getArrivalSeconds()).isEqualTo(39_600);
        assertThat(request.getStops(1).getStopSequence()).isEqualTo(2);
        assertThat(request.getStops(1).hasStopDesc()).isFalse();
        assertThat(request.getStops(1).getArrivalSeconds()).isEqualTo(88_500);
        assertThat(upstreams.authorizations).containsExactly("Bearer prediction-token");
    }

    @Test
    void rowsWithoutADelayStayWithoutOneAndZeroAndNegativeDelaysAreKept() {
        upstreams.prediction.response = PredictArrivalsResponse.newBuilder()
                .addPredictions(PredictedArrival.newBuilder().setStopSequence(2).setPredictedDelaySeconds(0))
                .addPredictions(PredictedArrival.newBuilder().setStopSequence(3).setPredictedDelaySeconds(-45))
                .addPredictions(PredictedArrival.newBuilder().setStopSequence(4)).build();

        assertThat(source.predict(REQUEST).secondsBySequence()).containsExactlyInAnyOrderEntriesOf(Map.of(2, 0, 3, -45));
    }

    @Test
    void anEmptyResultHasNoDelays() {
        assertThat(source.predict(REQUEST)).isEqualTo(PredictedDelays.NONE);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "INVALID_ARGUMENT", "UNAUTHENTICATED", "INTERNAL", "UNIMPLEMENTED"})
    void everyFailureLeavesTheRowsWithoutPredictions(Status.Code code) {
        upstreams.prediction.failure = code.toStatus();

        assertThat(source.predict(REQUEST)).isEqualTo(PredictedDelays.NONE);
    }

    @Test
    void aSlowServiceHitsTheDeadlineAndTheNextAnswerIsUsedAgain() {
        upstreams.prediction.delay = Duration.ofSeconds(2);
        assertThat(source.predict(REQUEST)).isEqualTo(PredictedDelays.NONE);

        upstreams.prediction.delay = Duration.ZERO;
        upstreams.prediction.response = PredictArrivalsResponse.newBuilder()
                .addPredictions(PredictedArrival.newBuilder().setStopSequence(2).setPredictedDelaySeconds(30)).build();
        assertThat(source.predict(REQUEST).forSequence(2)).hasValue(30);
    }

    private static FakeUpstreams startUpstreams() {
        try {
            return FakeUpstreams.start("prediction-" + UUID.randomUUID());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
