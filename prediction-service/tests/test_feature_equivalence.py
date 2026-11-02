from __future__ import annotations

import csv
import gzip
import json
import math
from datetime import date, datetime, timedelta
from pathlib import Path
from types import MappingProxyType

import numpy as np
import pytest

from krktransit_prediction.adapter.bundle import model_context
from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.domain.calendar import WARSAW
from krktransit_prediction.domain.feature_row import FEATURE_NAMES
from krktransit_prediction.domain.model_types import StopEvent, TripHistory, TripKey, TripStop
from krktransit_prediction.domain.prediction_inputs import request_direction, target_rows
from krktransit_prediction.domain.segment_features import delay_observations, history_cutoff, recent_delays
from krktransit_prediction.domain.trip_request import trip_request

SERVICE = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures"
BUNDLE = SERVICE / "bundle"
CATEGORICAL = frozenset(json.loads((BUNDLE / "manifest.json").read_text())["categorical_features"])
FLOAT32 = frozenset(json.loads((BUNDLE / "manifest.json").read_text())["float32_features"])
TRAINING_RULE_FEATURES = ("recent_current_delay_mean_4_sec", "recent_current_delay_mean_8_sec")


def read_csv(name: str) -> list[dict[str, str]]:
    with gzip.open(FIXTURES / name, "rt", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def fixture_event(row: dict[str, str], identifier: int) -> tuple[StopEvent, bool]:
    happened = datetime.fromisoformat(row["event_time"])
    event = StopEvent(identifier, TripKey(row["trip_id"], date.fromisoformat(row["service_date"])),
                      int(row["stop_sequence"]), int(row["max_stop_sequence"]), row["stop_name"],
                      row["stop_desc"] or None, datetime.fromisoformat(row["planned_time"]), happened,
                      int(row["delay_seconds"]), True, happened)
    return event, row["training_anchor"] == "1"


@pytest.fixture(scope="module")
def fixture():
    pairs = [fixture_event(row, index) for index, row in enumerate(read_csv("feature_events.csv.gz"))]
    events = [event for event, _ in pairs]
    anchors = frozenset((event.trip, event.sequence) for event, flag in pairs if flag)
    context = model_context(BUNDLE, json.loads((BUNDLE / "manifest.json").read_text()))
    planned: dict[TripKey, dict[int, datetime]] = {}
    for row in read_csv("feature_schedule.csv.gz"):
        trip = TripKey(row["trip_id"], date.fromisoformat(row["service_date"]))
        planned.setdefault(trip, {})[int(row["stop_sequence"])] = datetime.fromisoformat(row["planned_time"])
    return events, anchors, context, read_csv("feature_rows.csv.gz"), planned


def schedule(events: dict[tuple[TripKey, int], StopEvent], planned: dict[int, datetime], trip: TripKey) -> list[TripStop]:
    midnight = datetime.combine(trip.service_date, datetime.min.time(), WARSAW)
    return [trip_stop(events.get((trip, sequence)), sequence, int((time - midnight).total_seconds()))
            for sequence, time in sorted(planned.items())]


def trip_stop(event: StopEvent | None, sequence: int, arrival_seconds: int) -> TripStop:
    if event is None:
        return TripStop(sequence, "", None, arrival_seconds)
    return TripStop(sequence, event.stop_name, event.stop_desc, arrival_seconds)


def expected_value(name: str, text: str) -> object:
    if name in CATEGORICAL:
        return text or None
    number = math.nan if text == "" else float(text)
    return float(np.float32(number)) if name in FLOAT32 else number


def served_value(name: str, value: object) -> object:
    if name in CATEGORICAL:
        return None if value is None else str(value)
    number = math.nan if value is None else float(value)
    return float(np.float32(number)) if name in FLOAT32 else number


def same(left: object, right: object) -> bool:
    if isinstance(left, float) and isinstance(right, float):
        return (math.isnan(left) and math.isnan(right)) or left == right
    return left == right


def anchor_of(lookup: dict[tuple[TripKey, int], StopEvent], row: dict[str, str]) -> StopEvent:
    trip = TripKey(row["trip_id"], date.fromisoformat(row["service_date"]))
    return lookup[(trip, int(row["current_stop_sequence"]))]


def training_rule_delays(trips: tuple[TripHistory, ...], anchors: frozenset, direction: int, anchor: StopEvent,
                         lag: int):
    restricted = [TripHistory(t.trip, MappingProxyType({s: e for s, e in t.events.items() if (t.trip, s) in anchors}),
                              t.direction) for t in trips]
    return recent_delays(delay_observations(restricted, direction, anchor.key), history_cutoff(anchor, lag))


def test_served_features_match_the_training_code_on_replayed_events(fixture):
    events, anchors, context, expected_rows, planned = fixture
    by_time = sorted(events, key=lambda e: e.event_time)
    projection = EventProjection(context.directions, timedelta(days=30))
    visible, mismatches, deviations = 0, [], 0
    lookup = {(e.trip, e.sequence): e for e in events}
    for row in sorted(expected_rows, key=lambda r: anchor_of(lookup, r).event_time):
        trip = TripKey(row["trip_id"], date.fromisoformat(row["service_date"]))
        anchor = anchor_of(lookup, row)
        while visible < len(by_time) and by_time[visible].event_time <= anchor.event_time:
            projection.apply("179", [by_time[visible]])
            visible += 1
        target = anchor.sequence + int(row["target_horizon"])
        request = trip_request("krakow", trip.trip_id, trip.service_date, "179", int(row["max_stop_sequence"]),
                               schedule(lookup, planned[trip], trip), [target])
        snapshot = projection.snapshot("179", trip)
        direction = request_direction(request, context.directions)
        assert direction == int(row["direction_id"]), row["row_id"]
        served = target_rows(request, anchor, direction, snapshot.own_events, snapshot.line_trips, [target], context)
        assert len(served) == 1, row["row_id"]
        values = dict(served[0].values)
        training = training_rule_delays(snapshot.line_trips, anchors, direction, anchor, context.history_lag_seconds)
        if (values["recent_current_delay_mean_4_sec"], values["recent_current_delay_mean_8_sec"]) != (
                training.mean_4, training.mean_8):
            deviations += 1
        values["recent_current_delay_mean_4_sec"], values["recent_current_delay_mean_8_sec"] = (
            training.mean_4, training.mean_8)
        for name in FEATURE_NAMES:
            if not same(served_value(name, values[name]), expected_value(name, row[name])):
                mismatches.append((row["row_id"], name, values[name], row[name]))
    assert mismatches == []
    assert deviations < len(expected_rows) / 4
