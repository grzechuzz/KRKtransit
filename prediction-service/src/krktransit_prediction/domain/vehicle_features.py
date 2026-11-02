from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass

from krktransit_prediction.domain.anchor import FIRST_ANCHOR_SEQUENCE
from krktransit_prediction.domain.model_types import StopEvent, TripRequest

PREVIOUS_OFFSETS = (1, 2, 3, 4)
NEXT_SEGMENTS = (1, 2, 3, 4, 5)
MISSING = math.nan


@dataclass(frozen=True, slots=True)
class PreviousStop:
    delay: float
    key: str | None


@dataclass(frozen=True, slots=True)
class OwnTripFeatures:
    current_delay: int
    previous: tuple[PreviousStop, ...]
    delay_delta_1_2: float
    mean_delay_delta_last_4: float


def own_trip_features(anchor: StopEvent, events: Mapping[int, StopEvent]) -> OwnTripFeatures:
    previous = tuple(previous_stop(events, anchor.sequence - offset) for offset in PREVIOUS_OFFSETS)
    delays = [stop.delay for stop in reversed(previous)] + [float(anchor.delay_seconds)]
    return OwnTripFeatures(anchor.delay_seconds, previous, anchor.delay_seconds - previous[0].delay,
                           mean_change(delays))


def previous_stop(events: Mapping[int, StopEvent], sequence: int) -> PreviousStop:
    event = events.get(sequence)
    if not usable_history(event, sequence):
        return PreviousStop(MISSING, None)
    return PreviousStop(float(event.delay_seconds), event.key)


def usable_history(event: StopEvent | None, sequence: int) -> bool:
    return sequence >= FIRST_ANCHOR_SEQUENCE and event is not None and event.observed


def mean_change(delays: list[float]) -> float:
    changes = [right - left for left, right in zip(delays, delays[1:]) if not math.isnan(left) and not math.isnan(right)]
    return sum(changes) / len(changes) if changes else MISSING


@dataclass(frozen=True, slots=True)
class ScheduleFeatures:
    travel_time_to_target: int
    next_segments: tuple[float, ...]


def schedule_features(request: TripRequest, anchor_sequence: int, target_sequence: int) -> ScheduleFeatures | None:
    anchor, target = request.stop(anchor_sequence), request.stop(target_sequence)
    if anchor is None or target is None:
        return None
    segments = tuple(next_segment(request, anchor_sequence, step) for step in NEXT_SEGMENTS)
    return ScheduleFeatures(target.arrival_seconds - anchor.arrival_seconds, segments)


def next_segment(request: TripRequest, anchor_sequence: int, step: int) -> float:
    if anchor_sequence + step >= request.max_sequence:
        return MISSING
    start, end = request.stop(anchor_sequence + step - 1), request.stop(anchor_sequence + step)
    if start is None or end is None:
        return MISSING
    duration = end.arrival_seconds - start.arrival_seconds
    return float(duration) if duration > 0 else MISSING
