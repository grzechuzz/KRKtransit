from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from datetime import datetime, timedelta

from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.application.event_sync import LineEventSync, SyncResult
from krktransit_prediction.application.ports import Clock, LineEventsUnavailable
from krktransit_prediction.application.prediction_stats import PredictionStats, StatsSummary

LOG = logging.getLogger(__name__)


class PeriodicTask:
    def __init__(self, name: str, interval: timedelta, action: Callable[[], None]) -> None:
        self._interval = interval.total_seconds()
        self._action = action
        self._stopped = threading.Event()
        self._thread = threading.Thread(target=self._run, name=name, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stopped.set()
        self._thread.join(timeout=self._interval + 5)

    def _run(self) -> None:
        while not self._stopped.wait(self._interval):
            self._run_once()

    def _run_once(self) -> None:
        try:
            self._action()
        except Exception:
            LOG.exception("Periodic task %s failed", self._thread.name)


class ProjectionRefresher:
    def __init__(self, sync: LineEventSync, projection: EventProjection, lines: frozenset[str],
                 stats: PredictionStats, clock: Clock) -> None:
        self._sync = sync
        self._projection = projection
        self._lines = tuple(sorted(lines))
        self._stats = stats
        self._clock = clock
        self._available = True
        self._last_success: dict[str, datetime] = {}

    def refresh_all(self) -> None:
        for line in self._lines:
            self._refresh(line)
        self._projection.expire(self._clock())

    def oldest_success(self) -> datetime | None:
        if len(self._last_success) < len(self._lines):
            return None
        return min(self._last_success.values())

    def _refresh(self, line: str) -> None:
        try:
            result = self._sync.refresh(line)
        except LineEventsUnavailable as failure:
            self._unavailable(line, failure)
            return
        self._succeeded(line, result)

    def _unavailable(self, line: str, failure: LineEventsUnavailable) -> None:
        if self._available:
            LOG.warning("Stop Events unavailable while reading line %s: %s", line, failure)
        self._available = False

    def _succeeded(self, line: str, result: SyncResult) -> None:
        if not self._available:
            LOG.info("Stop Events available again")
        self._available = True
        self._last_success[line] = self._clock()
        self._stats.events_fetched(result.fetched)
        if result.backfill:
            self._log_backfill(line, result)

    def _log_backfill(self, line: str, result: SyncResult) -> None:
        summary = self._projection.summary(line)
        LOG.info("Backfilled line %s: %d events fetched, %d stored, oldest %s, newest %s", line, result.fetched,
                 summary.events, summary.oldest_event_time, summary.newest_event_time)


class SummaryReporter:
    def __init__(self, stats: PredictionStats, refresher: ProjectionRefresher, clock: Clock) -> None:
        self._stats = stats
        self._refresher = refresher
        self._clock = clock

    def report(self) -> None:
        LOG.info("Summary: %s, projection age %s", describe(self._stats.drain()), self._projection_age())

    def _projection_age(self) -> str:
        oldest = self._refresher.oldest_success()
        if oldest is None:
            return "unknown (not every line read yet)"
        return f"{(self._clock() - oldest).total_seconds():.0f} s"


def describe(summary: StatsSummary) -> str:
    empty = ", ".join(f"{reason.value} {count}" for reason, count in sorted(summary.empty.items(),
                                                                          key=lambda item: item[0].value)) or "none"
    mean = "n/a" if summary.mean_computation_ms is None else f"{summary.mean_computation_ms:.1f} ms"
    return (f"{summary.events_fetched} events fetched, {summary.computed} predictions computed, "
            f"{summary.cache_hits} cache hits, empty results: {empty}, mean computation {mean}")
