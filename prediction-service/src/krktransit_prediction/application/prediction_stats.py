from __future__ import annotations

import threading
from collections import Counter
from dataclasses import dataclass, field
from enum import Enum


class EmptyReason(Enum):
    OUT_OF_SCOPE = "out_of_scope"
    NO_ANCHOR = "no_anchor"
    UNKNOWN_DIRECTION = "unknown_direction"
    UNSUPPORTED_HORIZON = "unsupported_horizon"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class StatsSummary:
    events_fetched: int
    computed: int
    cache_hits: int
    empty: dict[EmptyReason, int] = field(default_factory=dict)
    mean_computation_ms: float | None = None


class PredictionStats:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._reset()

    def events_fetched(self, count: int) -> None:
        with self._lock:
            self._events += count

    def computed(self, milliseconds: float) -> None:
        with self._lock:
            self._computed += 1
            self._computation_ms += milliseconds

    def cache_hit(self) -> None:
        with self._lock:
            self._cache_hits += 1

    def empty(self, reason: EmptyReason) -> None:
        with self._lock:
            self._empty[reason] += 1

    def drain(self) -> StatsSummary:
        with self._lock:
            mean = self._computation_ms / self._computed if self._computed else None
            summary = StatsSummary(self._events, self._computed, self._cache_hits, dict(self._empty), mean)
            self._reset()
            return summary

    def _reset(self) -> None:
        self._events = 0
        self._computed = 0
        self._cache_hits = 0
        self._computation_ms = 0.0
        self._empty: Counter[EmptyReason] = Counter()
