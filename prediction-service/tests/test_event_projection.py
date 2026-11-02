from datetime import timedelta

import pytest

from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.application.event_sync import LineEventSync, SyncSettings
from krktransit_prediction.application.ports import EventCursor, LineEventPage, LineEventsUnavailable
from krktransit_prediction.domain.directions import CanonicalDirections
from krktransit_prediction.domain.model_types import TripKey
from prediction_support import SERVICE_DATE, START, TRIP, event

DIRECTIONS = CanonicalDirections({"139": (tuple(f"stop {i}|01" for i in range(1, 11)),
                                          tuple(f"stop {i}|01" for i in range(10, 0, -1)))})
NOW = START + timedelta(hours=1)


class FakeSource:
    def __init__(self, events):
        self.events = sorted(events, key=lambda e: (e.created_at, e.event_id))
        self.requests = []

    def page(self, line_number, cursor, page_size):
        self.requests.append(cursor)
        after = [e for e in self.events if after_cursor(e, cursor)]
        page = tuple(after[:page_size])
        next_cursor = EventCursor(page[-1].created_at, page[-1].event_id) if page else cursor
        return LineEventPage(page, next_cursor, len(after) > page_size)


def after_cursor(event, cursor):
    if cursor.after_id is None:
        return event.created_at > cursor.created_after
    return (event.created_at, event.event_id) > (cursor.created_after, cursor.after_id)


def sync_for(source, clock=lambda: NOW, page_size=2):
    projection = EventProjection(DIRECTIONS, timedelta(days=8))
    return projection, LineEventSync(source, projection, clock,
                                     SyncSettings(timedelta(days=7), timedelta(minutes=2), page_size))


def test_backfill_pages_until_the_end_and_then_polls_with_an_overlap():
    events = [event(seq, event_id=seq, created_at=START + timedelta(minutes=seq)) for seq in range(1, 6)]
    source = FakeSource(events)
    projection, sync = sync_for(source)
    result = sync.refresh("139")
    assert (result.fetched, result.added, result.backfill) == (5, 5, True)
    assert source.requests[0] == EventCursor(NOW - timedelta(days=7), None)
    assert [r.after_id for r in source.requests[1:]] == [2, 4]
    source.requests.clear()
    second = sync.refresh("139")
    assert source.requests[0] == EventCursor(START + timedelta(minutes=3), None)
    assert (second.fetched, second.added, second.backfill) == (2, 0, False)
    assert sorted(projection.snapshot("139", TRIP).own_events) == [1, 2, 3, 4, 5]


def test_a_late_earlier_event_fills_history_without_replacing_the_later_anchor():
    source = FakeSource([event(2, created_at=START), event(4, created_at=START + timedelta(minutes=1))])
    projection, sync = sync_for(source)
    sync.refresh("139")
    source.events.append(event(3, created_at=START + timedelta(minutes=2)))
    assert sync.refresh("139").added == 1
    assert sorted(projection.snapshot("139", TRIP).own_events) == [2, 3, 4]


def test_partial_trips_get_a_direction_once_their_events_identify_it():
    projection = EventProjection(DIRECTIONS, timedelta(days=8))
    other = TripKey("other", SERVICE_DATE)
    projection.apply("139", [event(3, trip=other)])
    assert projection.snapshot("139", TRIP).line_trips[0].direction == -1
    projection.apply("139", [event(4, trip=other)])
    assert projection.snapshot("139", TRIP).line_trips[0].direction == 0
    projection.apply("139", [event(5, trip=TRIP, name="Stop 6"), event(6, trip=TRIP, name="Stop 5")])
    assert {t.trip: t.direction for t in projection.snapshot("139", TRIP).line_trips}[TRIP] == 1


def test_trips_whose_newest_event_is_older_than_the_retention_are_dropped():
    projection = EventProjection(DIRECTIONS, timedelta(days=8))
    old = TripKey("old", SERVICE_DATE)
    projection.apply("139", [event(2, trip=old), event(2)])
    assert projection.expire(START + timedelta(days=8, minutes=3)) == 0
    projection.apply("139", [event(3, minute=60 * 24)])
    assert projection.expire(START + timedelta(days=8, minutes=10)) == 1
    assert [t.trip for t in projection.snapshot("139", TRIP).line_trips] == [TRIP]
    assert projection.summary("139").events == 2
    assert projection.summary("179").newest_event_time is None


def test_snapshots_are_isolated_from_later_updates():
    projection = EventProjection(DIRECTIONS, timedelta(days=8))
    projection.apply("139", [event(2)])
    snapshot = projection.snapshot("139", TRIP)
    projection.apply("139", [event(3)])
    assert sorted(snapshot.own_events) == [2]
    assert sorted(projection.snapshot("139", TRIP).own_events) == [2, 3]


def test_a_failed_backfill_is_retried_from_the_start():
    class Failing(FakeSource):
        fail = True

        def page(self, line_number, cursor, page_size):
            if self.fail:
                raise LineEventsUnavailable("offline")
            return super().page(line_number, cursor, page_size)

    source = Failing([event(2, created_at=START)])
    projection, sync = sync_for(source)
    with pytest.raises(LineEventsUnavailable):
        sync.refresh("139")
    assert not sync.backfilled("139")
    source.fail = False
    assert sync.refresh("139").backfill
    assert sync.backfilled("139")
