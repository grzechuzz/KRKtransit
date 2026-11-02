from __future__ import annotations

from collections.abc import Mapping, Sequence

from krktransit_prediction.domain.model_types import StopEvent

FIRST_ANCHOR_SEQUENCE = 2


def select_anchor(events: Mapping[int, StopEvent], max_sequence: int) -> StopEvent | None:
    eligible = [sequence for sequence in events if anchor_sequence_allowed(sequence, max_sequence)]
    return events[max(eligible)] if eligible else None


def anchor_sequence_allowed(sequence: int, max_sequence: int) -> bool:
    return FIRST_ANCHOR_SEQUENCE <= sequence <= max_sequence - 1


def supported_targets(anchor_sequence: int, max_sequence: int, horizons: Sequence[int]) -> tuple[int, ...]:
    return tuple(anchor_sequence + horizon for horizon in horizons
                 if target_sequence_allowed(anchor_sequence + horizon, max_sequence))


def target_sequence_allowed(sequence: int, max_sequence: int) -> bool:
    return sequence <= max_sequence - 1
