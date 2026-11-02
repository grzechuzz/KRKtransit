import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.application.predict_arrivals import PredictArrivals, PredictionScope
from krktransit_prediction.application.prediction_cache import PredictionCache
from krktransit_prediction.application.prediction_stats import EmptyReason, PredictionStats
from krktransit_prediction.domain.calendar import Holidays
from krktransit_prediction.domain.directions import CanonicalDirections
from krktransit_prediction.domain.model_types import TripKey, TripStop
from krktransit_prediction.domain.prediction_inputs import ModelContext
from krktransit_prediction.domain.segment_profile import SegmentProfile
from prediction_support import START, TRIP, event, request

ROUTE = tuple(f"stop {i}|01" for i in range(1, 11))
DIRECTIONS = CanonicalDirections({"139": (ROUTE, tuple(reversed(ROUTE)))})
CONTEXT = ModelContext(DIRECTIONS, Holidays([]), SegmentProfile({}), (1, 2, 3, 4, 5), 30)


class FakeModel:
    version = "test-model"

    def __init__(self, gain=12.4, gate=None):
        self.gain, self.gate, self.calls = gain, gate, []

    def predict_gains(self, rows):
        self.calls.append([(row["target_horizon"], row["current_stop_sequence"]) for row in rows])
        if self.gate:
            self.gate.wait(5)
        return [self.gain * row["target_horizon"] for row in rows]


class FakeLog:
    def __init__(self):
        self.records = []

    def record(self, predictions):
        self.records.append(predictions)


def use_case(model=None, events=(), clock=lambda: START + timedelta(hours=1)):
    projection = EventProjection(DIRECTIONS, timedelta(days=8))
    projection.apply("139", events)
    log, stats = FakeLog(), PredictionStats()
    arrivals = PredictArrivals(projection, model or FakeModel(), CONTEXT, PredictionScope("krakow", frozenset({"139"})),
                               PredictionCache(100, timedelta(hours=6), clock), log, stats, clock)
    return arrivals, projection, log, stats


def test_predicts_all_horizons_from_the_anchor_and_returns_the_requested_rows():
    model = FakeModel()
    arrivals, _, log, stats = use_case(model, [event(2, delay=30), event(3, delay=60)])
    outcome = arrivals.predict(request(targets=(3, 4, 5)))
    assert outcome.predictions.anchor_sequence == 3
    assert outcome.delays == {4: 72, 5: 85}
    assert model.calls == [[(1, 3), (2, 3), (3, 3), (4, 3), (5, 3)]]
    assert [p.target_sequence for p in log.records[0].predictions] == [4, 5, 6, 7, 8]
    assert stats.drain().computed == 1


def test_a_cached_anchor_is_reused_for_other_rows_and_not_logged_again():
    model = FakeModel()
    arrivals, _, log, stats = use_case(model, [event(2), event(3)])
    first = arrivals.predict(request(targets=(4,)))
    second = arrivals.predict(request(targets=(5, 6)))
    assert len(model.calls) == 1 and len(log.records) == 1
    assert second.predictions.predicted_at == first.predictions.predicted_at
    assert sorted(second.delays) == [5, 6]
    assert stats.drain().cache_hits == 1


def test_a_new_anchor_computes_again():
    model = FakeModel()
    arrivals, projection, log, _ = use_case(model, [event(2), event(3)])
    arrivals.predict(request(targets=(4,)))
    projection.apply("139", [event(4, delay=5)])
    outcome = arrivals.predict(request(targets=(5,)))
    assert outcome.predictions.anchor_sequence == 4 and len(log.records) == 2


def test_an_estimated_anchor_is_used_and_flagged():
    arrivals, _, log, _ = use_case(events=[event(2), event(3, observed=False)])
    outcome = arrivals.predict(request(targets=(4,)))
    assert outcome.predictions.anchor_estimated and log.records[0].anchor_estimated


def test_empty_results_have_explicit_reasons():
    arrivals, _, _, stats = use_case(events=[event(2), event(9)])
    assert arrivals.predict(request(line="179")).empty_reason is EmptyReason.OUT_OF_SCOPE
    assert arrivals.predict(request(city="warsaw")).empty_reason is EmptyReason.OUT_OF_SCOPE
    other_trip = request(trip=TripKey("unknown", TRIP.service_date))
    assert arrivals.predict(other_trip).empty_reason is EmptyReason.NO_ANCHOR
    assert arrivals.predict(request(targets=(10,))).empty_reason is EmptyReason.UNSUPPORTED_HORIZON
    unmatched = request(trip_stops=[TripStop(s.sequence, "Elsewhere", None, s.arrival_seconds)
                                    for s in request().stops.values()])
    assert arrivals.predict(unmatched).empty_reason is EmptyReason.UNKNOWN_DIRECTION
    assert stats.drain().empty == {EmptyReason.OUT_OF_SCOPE: 2, EmptyReason.NO_ANCHOR: 1,
                                   EmptyReason.UNSUPPORTED_HORIZON: 1, EmptyReason.UNKNOWN_DIRECTION: 1}


def test_overlapping_requests_compute_and_log_once():
    gate = threading.Event()
    model = FakeModel(gate=gate)
    arrivals, _, log, stats = use_case(model, [event(2), event(3)])
    with ThreadPoolExecutor(4) as pool:
        futures = [pool.submit(arrivals.predict, request(targets=(4,))) for _ in range(4)]
        threading.Timer(0.2, gate.set).start()
        outcomes = [future.result(5) for future in futures]
    assert len(model.calls) == 1 and len(log.records) == 1
    assert {outcome.delays[4] for outcome in outcomes} == {12}
    summary = stats.drain()
    assert (summary.computed, summary.cache_hits) == (1, 3)
