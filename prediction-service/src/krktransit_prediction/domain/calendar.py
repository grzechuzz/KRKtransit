from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime
from zoneinfo import ZoneInfo

WARSAW = ZoneInfo("Europe/Warsaw")
WEEKEND_DAYS = frozenset({6, 7})
MINUTES_PER_DAY = 1440


class Holidays:
    def __init__(self, days: Iterable[date]) -> None:
        self._days = frozenset(days)

    def __contains__(self, day: date) -> bool:
        return day in self._days


@dataclass(frozen=True, slots=True)
class CalendarFeatures:
    day_of_week: int
    minute_of_day: int
    time_sin: float
    time_cos: float
    non_working_day: bool


def calendar_features(planned_time: datetime, service_date: date, holidays: Holidays) -> CalendarFeatures:
    local = planned_time.astimezone(WARSAW)
    minute = local.hour * 60 + local.minute
    day_of_week = local.isoweekday()
    return CalendarFeatures(day_of_week, minute, math.sin(2 * math.pi * minute / MINUTES_PER_DAY),
                            math.cos(2 * math.pi * minute / MINUTES_PER_DAY),
                            day_of_week in WEEKEND_DAYS or service_date in holidays)
