package pl.krktransit.stopevents.adapter.inbound.grpc;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import pl.krktransit.stopevents.contracts.v1.LineEvent;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsRequest;
import pl.krktransit.stopevents.contracts.v1.ListLineEventsResponse;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;

final class LineEventsResponseMapper {
    ListLineEventsResponse response(ListLineEventsRequest request, LineEventsPage page) {
        var response = ListLineEventsResponse.newBuilder().setHasMore(page.hasMore());
        page.events().forEach(event -> response.addEvents(event(event)));
        page.last().ifPresentOrElse(
                last -> response.setNextCreatedAfter(timestamp(last.createdAt())).setNextAfterId(last.id()),
                () -> repeatCursor(request, response));
        return response.build();
    }

    private void repeatCursor(ListLineEventsRequest request, ListLineEventsResponse.Builder response) {
        response.setNextCreatedAfter(request.getCreatedAfter());
        if (request.hasAfterId()) response.setNextAfterId(request.getAfterId());
    }

    private LineEvent event(pl.krktransit.stopevents.domain.lineevents.LineEvent event) {
        var message = LineEvent.newBuilder().setId(event.id()).setSourceId(event.sourceId()).setTripId(event.tripId())
                .setServiceDate(event.serviceDate().toString()).setStopSequence(event.stopSequence())
                .setMaxStopSequence(event.maxStopSequence()).setStopId(event.stopId()).setStopName(event.stopName())
                .setPlannedTime(timestamp(event.plannedTime())).setEventTime(timestamp(event.eventTime()))
                .setDelaySeconds(event.delaySeconds()).setDetectionMethod(event.method().code())
                .setIsEstimated(event.estimated()).setCreatedAt(timestamp(event.createdAt()))
                .setHeadsign(event.headsign());
        if (event.stopDescription() != null) message.setStopDesc(event.stopDescription());
        return message.build();
    }

    private Timestamp timestamp(Instant time) {
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build();
    }
}
