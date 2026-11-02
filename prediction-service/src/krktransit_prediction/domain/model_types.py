from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, datetime

from krktransit_prediction.domain.stop_keys import stop_key


@dataclass(frozen=True, slots=True)
class TripKey:
    trip_id: str
    service_date: date


@dataclass(frozen=True, slots=True)
class TripStop:
    sequence: int
    stop_name: str
    stop_desc: str | None
    arrival_seconds: int
    key: str = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "key", stop_key(self.stop_name, self.stop_desc))


@dataclass(frozen=True, slots=True)
class StopEvent:
    event_id: int
    trip: TripKey
    sequence: int
    max_sequence: int
    stop_name: str
    stop_desc: str | None
    planned_time: datetime
    event_time: datetime
    delay_seconds: int
    observed: bool
    created_at: datetime
    key: str = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "key", stop_key(self.stop_name, self.stop_desc))


@dataclass(frozen=True, slots=True)
class TripRequest:
    city_id: str
    trip: TripKey
    line_number: str
    max_sequence: int
    stops: Mapping[int, TripStop]
    target_sequences: tuple[int, ...]

    def stop(self, sequence: int) -> TripStop | None:
        return self.stops.get(sequence)


@dataclass(frozen=True, slots=True)
class TripHistory:
    trip: TripKey
    events: Mapping[int, StopEvent]
    direction: int


@dataclass(frozen=True, slots=True)
class Prediction:
    target_sequence: int
    predicted_delay_seconds: int


@dataclass(frozen=True, slots=True)
class AnchorPredictions:
    trip: TripKey
    anchor_sequence: int
    anchor_estimated: bool
    predictions: tuple[Prediction, ...]
    predicted_at: datetime
    model_version: str

    def delay_for(self, sequence: int) -> int | None:
        return next((p.predicted_delay_seconds for p in self.predictions if p.target_sequence == sequence), None)
