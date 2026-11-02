from __future__ import annotations

import math
import time
from dataclasses import dataclass
from datetime import datetime

from krktransit_prediction.application.event_projection import EventProjection, ProjectionSnapshot
from krktransit_prediction.application.ports import Clock, DelayModel, PredictionLog
from krktransit_prediction.application.prediction_cache import PredictionCache
from krktransit_prediction.application.prediction_stats import EmptyReason, PredictionStats
from krktransit_prediction.domain.anchor import select_anchor, supported_targets
from krktransit_prediction.domain.directions import UNKNOWN_DIRECTION
from krktransit_prediction.domain.model_types import AnchorPredictions, Prediction, StopEvent, TripKey, TripRequest
from krktransit_prediction.domain.prediction_inputs import ModelContext, TargetRow, request_direction, target_rows


@dataclass(frozen=True, slots=True)
class PredictionScope:
    city_id: str
    line_numbers: frozenset[str]

    def covers(self, request: TripRequest) -> bool:
        return request.city_id == self.city_id and request.line_number in self.line_numbers


@dataclass(frozen=True, slots=True)
class CacheKey:
    trip: TripKey
    anchor_sequence: int
    anchor_event_time: datetime
    model_version: str


@dataclass(frozen=True, slots=True)
class PredictionOutcome:
    model_version: str
    predictions: AnchorPredictions | None
    delays: dict[int, int]
    empty_reason: EmptyReason | None


@dataclass(frozen=True, slots=True)
class PredictionPlan:
    request: TripRequest
    snapshot: ProjectionSnapshot
    anchor: StopEvent
    direction: int
    targets: tuple[int, ...]


class PredictArrivals:
    def __init__(self, projection: EventProjection, model: DelayModel, context: ModelContext, scope: PredictionScope,
                 cache: PredictionCache[AnchorPredictions], log: PredictionLog, stats: PredictionStats,
                 clock: Clock) -> None:
        self._projection = projection
        self._model = model
        self._context = context
        self._scope = scope
        self._cache = cache
        self._log = log
        self._stats = stats
        self._clock = clock

    def predict(self, request: TripRequest) -> PredictionOutcome:
        plan = self._plan(request)
        if isinstance(plan, EmptyReason):
            return self._empty(plan)
        key = CacheKey(request.trip, plan.anchor.sequence, plan.anchor.event_time, self._model.version)
        predictions, computed = self._cache.get_or_compute(key, lambda: self._compute(plan))
        if not computed:
            self._stats.cache_hit()
        delays = {sequence: delay for sequence in request.target_sequences
                  if (delay := predictions.delay_for(sequence)) is not None}
        return PredictionOutcome(self._model.version, predictions, delays, None)

    def _plan(self, request: TripRequest) -> PredictionPlan | EmptyReason:
        if not self._scope.covers(request):
            return EmptyReason.OUT_OF_SCOPE
        snapshot = self._projection.snapshot(request.line_number, request.trip)
        anchor = select_anchor(snapshot.own_events, request.max_sequence)
        if anchor is None:
            return EmptyReason.NO_ANCHOR
        direction = request_direction(request, self._context.directions)
        if direction == UNKNOWN_DIRECTION:
            return EmptyReason.UNKNOWN_DIRECTION
        targets = supported_targets(anchor.sequence, request.max_sequence, self._context.horizons)
        if not set(targets) & set(request.target_sequences):
            return EmptyReason.UNSUPPORTED_HORIZON
        return PredictionPlan(request, snapshot, anchor, direction, targets)

    def _compute(self, plan: PredictionPlan) -> AnchorPredictions:
        started = time.perf_counter()
        rows = target_rows(plan.request, plan.anchor, plan.direction, plan.snapshot.own_events,
                           plan.snapshot.line_trips, plan.targets, self._context)
        gains = self._model.predict_gains([row.values for row in rows]) if rows else []
        predictions = AnchorPredictions(plan.request.trip, plan.anchor.sequence, not plan.anchor.observed,
                                        predicted_delays(plan.anchor, rows, gains), self._clock(),
                                        self._model.version)
        self._log.record(predictions)
        self._stats.computed((time.perf_counter() - started) * 1000)
        return predictions

    def _empty(self, reason: EmptyReason) -> PredictionOutcome:
        self._stats.empty(reason)
        return PredictionOutcome(self._model.version, None, {}, reason)


def predicted_delays(anchor: StopEvent, rows: list[TargetRow], gains: list[float]) -> tuple[Prediction, ...]:
    return tuple(Prediction(row.sequence, whole_seconds(anchor.delay_seconds + gain)) for row, gain in zip(rows, gains))


def whole_seconds(value: float) -> int:
    return math.floor(value + 0.5)
