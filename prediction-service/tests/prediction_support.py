from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from types import MappingProxyType

from krktransit_prediction.domain.model_types import StopEvent, TripHistory, TripKey, TripStop
from krktransit_prediction.domain.trip_request import trip_request

SERVICE_DATE = date(2026, 10, 22)
START = datetime(2026, 10, 22, 8, 0, tzinfo=timezone.utc)
TRIP = TripKey("krakow:mpk-bus:trip", SERVICE_DATE)


def event(sequence: int, delay: int = 0, minute: float | None = None, trip: TripKey = TRIP, observed: bool = True,
          max_sequence: int = 10, name: str | None = None, desc: str | None = "01", event_id: int | None = None,
          created_at: datetime | None = None) -> StopEvent:
    planned = START + timedelta(minutes=2 * sequence)
    happened = START + timedelta(minutes=minute) if minute is not None else planned + timedelta(seconds=delay)
    return StopEvent(event_id if event_id is not None else sequence, trip, sequence, max_sequence,
                     name or f"Stop {sequence}", desc, planned, happened, delay, observed, created_at or happened)


def history(events: list[StopEvent], direction: int = 0, trip: TripKey = TRIP) -> TripHistory:
    return TripHistory(trip, MappingProxyType({e.sequence: e for e in events}), direction)


def stops(max_sequence: int = 10) -> list[TripStop]:
    return [TripStop(sequence, f"Stop {sequence}", "01", 8 * 3600 + 120 * sequence)
            for sequence in range(1, max_sequence + 1)]


def request(targets: tuple[int, ...] = (5,), max_sequence: int = 10, line: str = "139", trip: TripKey = TRIP,
            city: str = "krakow", trip_stops: list[TripStop] | None = None):
    return trip_request(city, trip.trip_id, trip.service_date, line, max_sequence,
                        trip_stops if trip_stops is not None else stops(max_sequence), targets)
