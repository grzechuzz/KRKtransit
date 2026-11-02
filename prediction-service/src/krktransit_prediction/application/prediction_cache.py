from __future__ import annotations

import threading
from collections import OrderedDict
from collections.abc import Callable, Hashable
from concurrent.futures import Future
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Generic, TypeVar

from krktransit_prediction.application.ports import Clock

Value = TypeVar("Value")


class SharedComputationFailed(Exception):
    pass


@dataclass(slots=True)
class Entry(Generic[Value]):
    future: Future[Value]
    created: datetime


class PredictionCache(Generic[Value]):
    def __init__(self, maximum_entries: int, ttl: timedelta, clock: Clock) -> None:
        if maximum_entries < 1:
            raise ValueError("Prediction cache size must be positive")
        self._maximum_entries = maximum_entries
        self._ttl = ttl
        self._clock = clock
        self._lock = threading.Lock()
        self._entries: OrderedDict[Hashable, Entry[Value]] = OrderedDict()

    def get_or_compute(self, key: Hashable, compute: Callable[[], Value]) -> tuple[Value, bool]:
        entry, owner = self._claim(key)
        if not owner:
            return entry.future.result(), False
        completed = False
        try:
            entry.future.set_result(compute())
            completed = True
        finally:
            if not completed:
                self._abandon(key, entry)
        return entry.future.result(), True

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)

    def _claim(self, key: Hashable) -> tuple[Entry[Value], bool]:
        now = self._clock()
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None and not self._expired(entry, now):
                self._entries.move_to_end(key)
                return entry, False
            entry = Entry(Future(), now)
            self._entries[key] = entry
            self._entries.move_to_end(key)
            self._evict()
            return entry, True

    def _expired(self, entry: Entry[Value], now: datetime) -> bool:
        return now - entry.created >= self._ttl

    def _evict(self) -> None:
        while len(self._entries) > self._maximum_entries:
            self._entries.popitem(last=False)

    def _abandon(self, key: Hashable, entry: Entry[Value]) -> None:
        with self._lock:
            if self._entries.get(key) is entry:
                del self._entries[key]
        entry.future.set_exception(SharedComputationFailed("The shared prediction computation failed"))
