from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass

SLOT_MINUTES = 30


@dataclass(frozen=True, slots=True)
class ProfileKey:
    line_number: str
    direction: int
    current_key: str
    target_key: str
    non_working_day: bool
    slot: int


@dataclass(frozen=True, slots=True)
class ProfileStats:
    mean: float
    median: float
    std: float
    count: int


MISSING_PROFILE = ProfileStats(math.nan, math.nan, math.nan, 0)


class SegmentProfile:
    def __init__(self, entries: Mapping[ProfileKey, ProfileStats]) -> None:
        self._entries = dict(entries)

    def __len__(self) -> int:
        return len(self._entries)

    def lookup(self, key: ProfileKey) -> ProfileStats:
        return self._entries.get(key, MISSING_PROFILE)


def slot_of(minute_of_day: int) -> int:
    return minute_of_day // SLOT_MINUTES
