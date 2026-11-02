import json
import math
from datetime import date, datetime, timezone
from pathlib import Path

from krktransit_prediction.domain.calendar import Holidays, calendar_features

HOLIDAYS = Holidays(date.fromisoformat(entry["date"]) for entry in
                    json.loads((Path(__file__).resolve().parents[1] / "bundle/holidays.json").read_text()))


def test_local_time_and_weekday_come_from_the_planned_time_in_warsaw():
    features = calendar_features(datetime(2026, 11, 2, 22, 30, tzinfo=timezone.utc), date(2026, 11, 2), HOLIDAYS)
    assert (features.day_of_week, features.minute_of_day) == (1, 23 * 60 + 30)
    assert features.time_sin == math.sin(2 * math.pi * 1410 / 1440)
    assert not features.non_working_day


def test_holiday_status_uses_the_service_date_and_weekends_use_the_local_weekday():
    after_midnight = datetime(2026, 11, 11, 23, 30, tzinfo=timezone.utc)
    assert calendar_features(after_midnight, date(2026, 11, 11), HOLIDAYS).non_working_day
    assert calendar_features(after_midnight, date(2026, 11, 12), HOLIDAYS).day_of_week == 4
    assert calendar_features(datetime(2026, 12, 24, 8, tzinfo=timezone.utc), date(2026, 12, 24), HOLIDAYS).non_working_day
    assert calendar_features(datetime(2026, 11, 7, 8, tzinfo=timezone.utc), date(2026, 11, 7), HOLIDAYS).non_working_day


def test_dst_days_use_the_local_offset():
    before = calendar_features(datetime(2027, 3, 28, 0, 30, tzinfo=timezone.utc), date(2027, 3, 28), HOLIDAYS)
    after = calendar_features(datetime(2027, 3, 28, 1, 30, tzinfo=timezone.utc), date(2027, 3, 28), HOLIDAYS)
    assert (before.minute_of_day, after.minute_of_day) == (90, 210)
    autumn = calendar_features(datetime(2026, 10, 25, 1, 30, tzinfo=timezone.utc), date(2026, 10, 25), HOLIDAYS)
    assert autumn.minute_of_day == 150
