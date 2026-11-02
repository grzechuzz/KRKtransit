from __future__ import annotations

import threading
from collections import defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta
from types import MappingProxyType

from krktransit_prediction.domain.anchor import anchor_sequence_allowed
from krktransit_prediction.domain.directions import CanonicalDirections
from krktransit_prediction.domain.model_types import StopEvent, TripHistory, TripKey

EMPTY_EVENTS: Mapping[int, StopEvent] = MappingProxyType({})


@dataclass(frozen=True, slots=True)
class ProjectionSnapshot:
    own_events: Mapping[int, StopEvent]
    line_trips: tuple[TripHistory, ...]


@dataclass(frozen=True, slots=True)
class LineSummary:
    events: int
    oldest_event_time: datetime | None
    newest_event_time: datetime | None


class EventProjection:
    def __init__(self, directions: CanonicalDirections, retention: timedelta) -> None:
        self._directions = directions
        self._retention = retention
        self._lock = threading.Lock()
        self._lines: dict[str, dict[TripKey, TripHistory]] = {}

    def apply(self, line_number: str, events: Iterable[StopEvent]) -> int:
        by_trip: dict[TripKey, list[StopEvent]] = defaultdict(list)
        for event in events:
            by_trip[event.trip].append(event)
        with self._lock:
            trips = self._lines.setdefault(line_number, {})
            return sum(self._merge(line_number, trips, trip, received) for trip, received in by_trip.items())

    def expire(self, now: datetime) -> int:
        oldest_allowed = now - self._retention
        with self._lock:
            expired = [(line, trip) for line, trips in self._lines.items()
                       for trip, history in trips.items() if newest_event_time(history) < oldest_allowed]
            for line, trip in expired:
                del self._lines[line][trip]
            return len(expired)

    def snapshot(self, line_number: str, trip: TripKey) -> ProjectionSnapshot:
        with self._lock:
            trips = self._lines.get(line_number, {})
            own = trips.get(trip)
            return ProjectionSnapshot(own.events if own else EMPTY_EVENTS, tuple(trips.values()))

    def summary(self, line_number: str) -> LineSummary:
        with self._lock:
            events = [event for history in self._lines.get(line_number, {}).values()
                      for event in history.events.values()]
        if not events:
            return LineSummary(0, None, None)
        times = [event.event_time for event in events]
        return LineSummary(len(events), min(times), max(times))

    def _merge(self, line_number: str, trips: dict[TripKey, TripHistory], trip: TripKey,
               received: list[StopEvent]) -> int:
        current = trips.get(trip)
        merged = dict(current.events) if current else {}
        added = sum(event.sequence not in merged for event in received)
        merged.update((event.sequence, event) for event in received)
        trips[trip] = TripHistory(trip, MappingProxyType(merged), self._direction(line_number, merged))
        return added

    def _direction(self, line_number: str, events: Mapping[int, StopEvent]) -> int:
        keys = [events[sequence].key for sequence in sorted(events) if route_event(events[sequence])]
        return self._directions.assign(line_number, keys)


def route_event(event: StopEvent) -> bool:
    return event.observed and anchor_sequence_allowed(event.sequence, event.max_sequence)


def newest_event_time(history: TripHistory) -> datetime:
    return max(event.event_time for event in history.events.values())
