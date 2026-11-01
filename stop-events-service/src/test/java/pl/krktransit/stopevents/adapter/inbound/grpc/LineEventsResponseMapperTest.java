package pl.krktransit.stopevents.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsRequest;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.lineevents.LineEvent;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;

class LineEventsResponseMapperTest {
    private static final Instant PLANNED = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-25T12:00:05.123456Z");
    private static final ListLineEventsRequest REQUEST = ListLineEventsRequest.newBuilder().setCityId("krakow")
            .setLineNumber("139").setCreatedAfter(Timestamp.newBuilder().setSeconds(100).setNanos(7)).setAfterId(9).build();
    private final LineEventsResponseMapper mapper = new LineEventsResponseMapper();

    @Test
    void preservesAbsentDescriptionAndKeepsZeroValues() {
        var response = mapper.response(REQUEST, new LineEventsPage(List.of(event(41, null, 0, 0)), false));
        var event = response.getEvents(0);
        assertThat(event.hasStopDesc()).isFalse();
        assertThat(event.hasStopSequence()).isTrue();
        assertThat(event.getStopSequence()).isZero();
        assertThat(event.hasDelaySeconds()).isTrue();
        assertThat(event.getDelaySeconds()).isZero();
        assertThat(event.getDetectionMethod()).isEqualTo(2);
        assertThat(event.getIsEstimated()).isTrue();
        assertThat(event.getHeadsign()).isEqualTo("Kombinat");
        assertThat(event.getServiceDate()).isEqualTo("2026-10-25");
        assertThat(event.getCreatedAt()).isEqualTo(Timestamp.newBuilder().setSeconds(CREATED.getEpochSecond())
                .setNanos(123_456_000).build());
        assertThat(response.hasHasMore()).isTrue();
        assertThat(response.getHasMore()).isFalse();
    }

    @Test
    void mapsEveryFieldOfAStoredEvent() {
        var event = mapper.response(REQUEST, new LineEventsPage(List.of(event(41, "01", 7, -35)), true)).getEvents(0);
        assertThat(event.getId()).isEqualTo(41);
        assertThat(event.getSourceId()).isEqualTo("mpk-bus");
        assertThat(event.getTripId()).isEqualTo("krakow:mpk-bus:trip");
        assertThat(event.getStopSequence()).isEqualTo(7);
        assertThat(event.getMaxStopSequence()).isEqualTo(30);
        assertThat(event.getStopId()).isEqualTo("krakow:mpk-bus:stop");
        assertThat(event.getStopName()).isEqualTo("Rondo Mogilskie");
        assertThat(event.getStopDesc()).isEqualTo("01");
        assertThat(event.getPlannedTime().getSeconds()).isEqualTo(PLANNED.getEpochSecond());
        assertThat(event.getEventTime().getSeconds()).isEqualTo(PLANNED.getEpochSecond() - 35);
        assertThat(event.getDelaySeconds()).isEqualTo(-35);
    }

    @Test
    void returnsTheLastEventAsTheNextCursor() {
        var response = mapper.response(REQUEST, new LineEventsPage(List.of(event(41, null, 2, 0), event(42, null, 3, 0)), true));
        assertThat(response.getNextAfterId()).isEqualTo(42);
        assertThat(response.getNextCreatedAfter().getNanos()).isEqualTo(123_456_000);
        assertThat(response.getHasMore()).isTrue();
    }

    @Test
    void anEmptyPageRepeatsTheRequestedCursorIncludingAnAbsentId() {
        var withId = mapper.response(REQUEST, new LineEventsPage(List.of(), false));
        assertThat(withId.getEventsList()).isEmpty();
        assertThat(withId.getNextCreatedAfter()).isEqualTo(REQUEST.getCreatedAfter());
        assertThat(withId.getNextAfterId()).isEqualTo(9);
        var withoutId = mapper.response(REQUEST.toBuilder().clearAfterId().build(), new LineEventsPage(List.of(), false));
        assertThat(withoutId.getNextCreatedAfter()).isEqualTo(REQUEST.getCreatedAfter());
        assertThat(withoutId.hasNextAfterId()).isFalse();
    }

    private static LineEvent event(long id, String description, int sequence, int delay) {
        return new LineEvent(id, "mpk-bus", "krakow:mpk-bus:trip", LocalDate.of(2026, 10, 25), sequence, 30,
                "krakow:mpk-bus:stop", "Rondo Mogilskie", description, PLANNED, PLANNED.plusSeconds(delay), delay,
                description == null ? DetectionMethod.SEQ_JUMP : DetectionMethod.STOPPED_AT, description == null,
                CREATED, "Kombinat");
    }
}
