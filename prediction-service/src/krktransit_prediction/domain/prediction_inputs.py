from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from krktransit_prediction.domain.anchor import anchor_sequence_allowed
from krktransit_prediction.domain.calendar import Holidays, calendar_features
from krktransit_prediction.domain.directions import CanonicalDirections
from krktransit_prediction.domain.feature_row import AnchorInputs, FeatureValue, TargetInputs, feature_row
from krktransit_prediction.domain.model_types import StopEvent, TripHistory, TripRequest
from krktransit_prediction.domain.segment_features import (
    delay_observations, history_cutoff, recent_delays, recent_segment, segment_traversals,
)
from krktransit_prediction.domain.segment_profile import ProfileKey, SegmentProfile, slot_of
from krktransit_prediction.domain.vehicle_features import own_trip_features, schedule_features


@dataclass(frozen=True, slots=True)
class ModelContext:
    directions: CanonicalDirections
    holidays: Holidays
    profile: SegmentProfile
    horizons: tuple[int, ...]
    history_lag_seconds: int


@dataclass(frozen=True, slots=True)
class TargetRow:
    sequence: int
    values: dict[str, FeatureValue]


def request_direction(request: TripRequest, directions: CanonicalDirections) -> int:
    keys = [request.stops[sequence].key for sequence in sorted(request.stops)
            if anchor_sequence_allowed(sequence, request.max_sequence)]
    return directions.assign(request.line_number, keys)


def target_rows(request: TripRequest, anchor: StopEvent, direction: int, own_events: Mapping[int, StopEvent],
                line_trips: Sequence[TripHistory], targets: Sequence[int], context: ModelContext) -> list[TargetRow]:
    inputs = anchor_inputs(request, anchor, direction, own_events, line_trips, context)
    rows = []
    for sequence in targets:
        target = target_inputs(request, inputs, line_trips, sequence, context)
        if target is not None:
            rows.append(TargetRow(sequence, feature_row(inputs, target)))
    return rows


def anchor_inputs(request: TripRequest, anchor: StopEvent, direction: int, own_events: Mapping[int, StopEvent],
                  line_trips: Sequence[TripHistory], context: ModelContext) -> AnchorInputs:
    cutoff = history_cutoff(anchor, context.history_lag_seconds)
    observations = delay_observations(line_trips, direction, anchor.key)
    return AnchorInputs(request.line_number, direction, request.max_sequence, anchor,
                        own_trip_features(anchor, own_events),
                        calendar_features(anchor.planned_time, request.trip.service_date, context.holidays),
                        recent_delays(observations, cutoff))


def target_inputs(request: TripRequest, inputs: AnchorInputs, line_trips: Sequence[TripHistory], sequence: int,
                  context: ModelContext) -> TargetInputs | None:
    stop = request.stop(sequence)
    schedule = schedule_features(request, inputs.anchor.sequence, sequence)
    if stop is None or schedule is None:
        return None
    cutoff = history_cutoff(inputs.anchor, context.history_lag_seconds)
    traversals = segment_traversals(line_trips, inputs.direction, inputs.anchor.key, stop.key, context.horizons)
    key = ProfileKey(inputs.line_number, inputs.direction, inputs.anchor.key, stop.key,
                     inputs.calendar.non_working_day, slot_of(inputs.calendar.minute_of_day))
    return TargetInputs(sequence, stop.key, schedule, recent_segment(traversals, cutoff), context.profile.lookup(key))
