from concurrent import futures
from datetime import datetime, timedelta, timezone

import grpc
import pytest
from google.protobuf.timestamp_pb2 import Timestamp

from krktransit.stopevents.v1 import line_events_service_pb2 as messages
from krktransit.stopevents.v1 import line_events_service_pb2_grpc as services
from krktransit_prediction.adapter.stop_events_client import GrpcLineEventSource
from krktransit_prediction.application.ports import EventCursor, LineEventsUnavailable

CREATED = datetime(2026, 11, 2, 7, 0, 0, 123456, tzinfo=timezone.utc)


def stamp(time: datetime) -> Timestamp:
    message = Timestamp()
    message.FromDatetime(time)
    return message


class LineEvents(services.LineEventsServiceServicer):
    def __init__(self):
        self.requests, self.metadata, self.failure = [], [], None

    def ListLineEvents(self, request, context):
        self.requests.append(request)
        self.metadata.append(dict(context.invocation_metadata()))
        if self.failure:
            context.abort(self.failure, "fixture failure")
        event = messages.LineEvent(id=7, source_id="mpk-bus", trip_id="krakow:mpk-bus:t", service_date="2026-11-02",
                                   stop_sequence=0, max_stop_sequence=20, stop_id="krakow:mpk-bus:s",
                                   stop_name="Rondo", planned_time=stamp(CREATED - timedelta(minutes=1)),
                                   event_time=stamp(CREATED), delay_seconds=0, detection_method=2, is_estimated=True,
                                   created_at=stamp(CREATED), headsign="Kombinat")
        return messages.ListLineEventsResponse(events=[event], next_created_after=stamp(CREATED), next_after_id=7,
                                               has_more=True)


@pytest.fixture
def stop_events():
    servicer = LineEvents()
    server = grpc.server(futures.ThreadPoolExecutor(2))
    services.add_LineEventsServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    yield servicer, channel
    channel.close()
    server.stop(0)


def test_requests_carry_the_cursor_token_and_page_size(stop_events):
    servicer, channel = stop_events
    source = GrpcLineEventSource(channel, "krakow", "secret", timedelta(seconds=5))
    source.page("139", EventCursor(CREATED, None), 2000)
    source.page("139", EventCursor(CREATED, 7), 500)
    first, second = servicer.requests
    assert (first.city_id, first.line_number, first.page_size, first.HasField("after_id")) == ("krakow", "139", 2000, False)
    assert first.created_after.ToDatetime(tzinfo=timezone.utc) == CREATED
    assert (second.after_id, second.page_size) == (7, 500)
    assert servicer.metadata[0]["authorization"] == "Bearer secret"


def test_events_are_mapped_with_absent_description_and_estimation(stop_events):
    _, channel = stop_events
    page = GrpcLineEventSource(channel, "krakow", "", timedelta(seconds=5)).page("139", EventCursor(CREATED, None), 10)
    event = page.events[0]
    assert (event.event_id, event.trip.trip_id, event.trip.service_date.isoformat()) == (7, "krakow:mpk-bus:t", "2026-11-02")
    assert (event.sequence, event.max_sequence, event.delay_seconds) == (0, 20, 0)
    assert event.stop_desc is None and not event.observed
    assert event.created_at == CREATED
    assert page.next_cursor == EventCursor(CREATED, 7) and page.has_more


def test_transport_failures_become_line_events_unavailable(stop_events):
    servicer, channel = stop_events
    servicer.failure = grpc.StatusCode.UNAVAILABLE
    with pytest.raises(LineEventsUnavailable, match="UNAVAILABLE"):
        GrpcLineEventSource(channel, "krakow", "", timedelta(seconds=5)).page("139", EventCursor(CREATED, None), 10)
