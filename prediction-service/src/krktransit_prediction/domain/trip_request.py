from __future__ import annotations

from collections.abc import Sequence
from datetime import date
from types import MappingProxyType

from krktransit_prediction.domain.model_types import TripKey, TripRequest, TripStop

FIRST_SEQUENCE = 1


class InvalidTripRequest(ValueError):
    pass


def trip_request(city_id: str, trip_id: str, service_date: date, line_number: str, max_sequence: int,
                 stops: Sequence[TripStop], target_sequences: Sequence[int]) -> TripRequest:
    require_text(city_id, "city_id")
    require_text(trip_id, "trip_id")
    require_text(line_number, "line_number")
    if max_sequence < FIRST_SEQUENCE:
        raise InvalidTripRequest("max_stop_sequence must be positive")
    by_sequence = {stop.sequence: stop for stop in stops}
    if len(by_sequence) != len(stops):
        raise InvalidTripRequest("stops must have distinct stop_sequence values")
    if any(not FIRST_SEQUENCE <= sequence <= max_sequence for sequence in by_sequence):
        raise InvalidTripRequest("stop_sequence values must be between 1 and max_stop_sequence")
    return TripRequest(city_id, TripKey(trip_id, service_date), line_number, max_sequence,
                       MappingProxyType(by_sequence), tuple(target_sequences))


def require_text(value: str, name: str) -> None:
    if not value or not value.strip():
        raise InvalidTripRequest(f"{name} is required")
