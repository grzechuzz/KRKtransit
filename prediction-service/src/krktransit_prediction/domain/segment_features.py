from __future__ import annotations

import math
import statistics
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime

from krktransit_prediction.domain.anchor import FIRST_ANCHOR_SEQUENCE
from krktransit_prediction.domain.model_types import StopEvent, TripHistory

SHORT_WINDOW = 4
LONG_WINDOW = 8
MAXIMUM_ABSOLUTE_GAIN = 3600
MISSING = math.nan


@dataclass(frozen=True, slots=True)
class Traversal:
    target_time: int
    tie: str
    gain: int


@dataclass(frozen=True, slots=True)
class DelayObservation:
    time: int
    tie: str
    delay: int


@dataclass(frozen=True, slots=True)
class RecentSegment:
    gain_mean_4: float
    gain_median_4: float
    gain_mean_8: float
    gain_median_8: float
    gain_last_1: float


@dataclass(frozen=True, slots=True)
class RecentDelays:
    mean_4: float
    mean_8: float


def epoch_seconds(time: datetime) -> int:
    return int(time.replace(microsecond=0).timestamp())


def history_cutoff(anchor: StopEvent, lag_seconds: int) -> int:
    return epoch_seconds(anchor.event_time) - lag_seconds


def recent_segment(traversals: Iterable[Traversal], cutoff: int) -> RecentSegment:
    gains = last_values(((t.target_time, t.tie, t.gain) for t in traversals), cutoff)
    if not gains:
        return RecentSegment(MISSING, MISSING, MISSING, MISSING, MISSING)
    short, long = gains[-SHORT_WINDOW:], gains[-LONG_WINDOW:]
    return RecentSegment(mean(short), median(short), mean(long), median(long), float(gains[-1]))


def recent_delays(observations: Iterable[DelayObservation], cutoff: int) -> RecentDelays:
    delays = last_values(((o.time, o.tie, o.delay) for o in observations), cutoff)
    if not delays:
        return RecentDelays(MISSING, MISSING)
    return RecentDelays(mean(delays[-SHORT_WINDOW:]), mean(delays[-LONG_WINDOW:]))


def last_values(entries: Iterable[tuple[int, str, int]], cutoff: int) -> list[int]:
    available = sorted(entry for entry in entries if entry[0] <= cutoff)
    return [value for _, _, value in available[-LONG_WINDOW:]]


def mean(values: Sequence[int]) -> float:
    return sum(values) / len(values)


def median(values: Sequence[int]) -> float:
    return float(statistics.median(values))


def segment_traversals(trips: Iterable[TripHistory], direction: int, current_key: str, target_key: str,
                       horizons: Sequence[int]) -> list[Traversal]:
    return [traversal for trip in trips if trip.direction == direction
            for traversal in trip_traversals(trip, current_key, target_key, horizons)]


def trip_traversals(trip: TripHistory, current_key: str, target_key: str, horizons: Sequence[int]) -> list[Traversal]:
    traversals = []
    for start in trip.events.values():
        if start.key != current_key or not history_anchor(start):
            continue
        for horizon in horizons:
            end = trip.events.get(start.sequence + horizon)
            if history_target(start, end) and end.key == target_key:
                traversals.append(traversal(trip, start, end, horizon))
    return traversals


def history_anchor(event: StopEvent) -> bool:
    return event.observed and FIRST_ANCHOR_SEQUENCE <= event.sequence <= event.max_sequence - 1


def history_target(start: StopEvent, end: StopEvent | None) -> bool:
    return (end is not None and end.observed and end.sequence <= start.max_sequence - 1
            and epoch_seconds(end.event_time) > epoch_seconds(start.event_time)
            and abs(end.delay_seconds - start.delay_seconds) <= MAXIMUM_ABSOLUTE_GAIN)


def traversal(trip: TripHistory, start: StopEvent, end: StopEvent, horizon: int) -> Traversal:
    return Traversal(epoch_seconds(end.event_time), row_tie(trip, start.sequence, horizon),
                     end.delay_seconds - start.delay_seconds)


def delay_observations(trips: Iterable[TripHistory], direction: int, current_key: str) -> list[DelayObservation]:
    return [DelayObservation(epoch_seconds(event.event_time), row_tie(trip, event.sequence, 1), event.delay_seconds)
            for trip in trips if trip.direction == direction
            for event in trip.events.values() if delay_history_event(event) and event.key == current_key]


def delay_history_event(event: StopEvent) -> bool:
    return event.observed and FIRST_ANCHOR_SEQUENCE <= event.sequence <= event.max_sequence - 2


def row_tie(trip: TripHistory, sequence: int, horizon: int) -> str:
    return f"{trip.trip.service_date.isoformat()}_{trip.trip.trip_id}_{sequence}_{horizon}"
