from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

import grpc
from google.protobuf.timestamp_pb2 import Timestamp

from krktransit.stopevents.v1 import line_events_service_pb2 as messages
from krktransit.stopevents.v1 import line_events_service_pb2_grpc as services
from krktransit_prediction.application.ports import EventCursor, LineEventPage, LineEventsUnavailable
from krktransit_prediction.domain.model_types import StopEvent, TripKey

OBSERVED_METHOD = 1


class GrpcLineEventSource:
    def __init__(self, channel: grpc.Channel, city_id: str, token: str, deadline: timedelta) -> None:
        self._stub = services.LineEventsServiceStub(channel)
        self._city_id = city_id
        self._metadata = (("authorization", f"Bearer {token}"),) if token else ()
        self._deadline = deadline.total_seconds()

    def page(self, line_number: str, cursor: EventCursor, page_size: int) -> LineEventPage:
        try:
            response = self._stub.ListLineEvents(self._request(line_number, cursor, page_size),
                                                 timeout=self._deadline, metadata=self._metadata)
        except grpc.RpcError as failure:
            raise LineEventsUnavailable(f"ListLineEvents failed with {failure.code().name}") from failure
        return LineEventPage(tuple(stop_event(event) for event in response.events), next_cursor(response),
                             response.has_more)

    def _request(self, line_number: str, cursor: EventCursor, page_size: int) -> messages.ListLineEventsRequest:
        request = messages.ListLineEventsRequest(city_id=self._city_id, line_number=line_number,
                                                 created_after=timestamp(cursor.created_after), page_size=page_size)
        if cursor.after_id is not None:
            request.after_id = cursor.after_id
        return request


def next_cursor(response: messages.ListLineEventsResponse) -> EventCursor:
    after_id = response.next_after_id if response.HasField("next_after_id") else None
    return EventCursor(instant(response.next_created_after), after_id)


def stop_event(event: messages.LineEvent) -> StopEvent:
    return StopEvent(event.id, TripKey(event.trip_id, date.fromisoformat(event.service_date)), event.stop_sequence,
                     event.max_stop_sequence, event.stop_name, event.stop_desc if event.HasField("stop_desc") else None,
                     instant(event.planned_time), instant(event.event_time), event.delay_seconds,
                     event.detection_method == OBSERVED_METHOD and not event.is_estimated, instant(event.created_at))


def timestamp(time: datetime) -> Timestamp:
    message = Timestamp()
    message.FromDatetime(time.astimezone(timezone.utc))
    return message


def instant(message: Timestamp) -> datetime:
    return message.ToDatetime(tzinfo=timezone.utc)
