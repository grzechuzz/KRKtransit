from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from krktransit_prediction.domain.feature_row import FeatureValue
from krktransit_prediction.domain.model_types import AnchorPredictions, StopEvent

Clock = Callable[[], datetime]


class LineEventsUnavailable(Exception):
    pass


@dataclass(frozen=True, slots=True)
class EventCursor:
    created_after: datetime
    after_id: int | None


@dataclass(frozen=True, slots=True)
class LineEventPage:
    events: tuple[StopEvent, ...]
    next_cursor: EventCursor
    has_more: bool


class LineEventSource(Protocol):
    def page(self, line_number: str, cursor: EventCursor, page_size: int) -> LineEventPage: ...


class DelayModel(Protocol):
    @property
    def version(self) -> str: ...

    def predict_gains(self, rows: Sequence[Mapping[str, FeatureValue]]) -> list[float]: ...


class PredictionLog(Protocol):
    def record(self, predictions: AnchorPredictions) -> None: ...
