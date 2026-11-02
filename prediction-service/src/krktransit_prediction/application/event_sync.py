from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.application.ports import Clock, EventCursor, LineEventSource


@dataclass(frozen=True, slots=True)
class SyncSettings:
    backfill: timedelta
    overlap: timedelta
    page_size: int


@dataclass(frozen=True, slots=True)
class SyncResult:
    line_number: str
    fetched: int
    added: int
    backfill: bool


class LineEventSync:
    def __init__(self, source: LineEventSource, projection: EventProjection, clock: Clock,
                 settings: SyncSettings) -> None:
        self._source = source
        self._projection = projection
        self._clock = clock
        self._settings = settings
        self._newest_created: dict[str, datetime] = {}

    def backfilled(self, line_number: str) -> bool:
        return line_number in self._newest_created

    def refresh(self, line_number: str) -> SyncResult:
        backfill = not self.backfilled(line_number)
        start = self._start(line_number, backfill)
        cursor, fetched, added, newest = EventCursor(start, None), 0, 0, self._newest_created.get(line_number, start)
        while True:
            page = self._source.page(line_number, cursor, self._settings.page_size)
            fetched += len(page.events)
            added += self._projection.apply(line_number, page.events)
            newest = max([newest, *(event.created_at for event in page.events)])
            if not page.has_more:
                break
            cursor = page.next_cursor
        self._newest_created[line_number] = newest
        return SyncResult(line_number, fetched, added, backfill)

    def _start(self, line_number: str, backfill: bool) -> datetime:
        if backfill:
            return self._clock() - self._settings.backfill
        return self._newest_created[line_number] - self._settings.overlap
