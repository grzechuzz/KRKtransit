import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest

from krktransit_prediction.application.prediction_cache import PredictionCache, SharedComputationFailed
from prediction_support import START


class Clock:
    def __init__(self):
        self.now = START

    def __call__(self):
        return self.now


def test_completed_results_are_reused_until_they_expire():
    clock = Clock()
    cache = PredictionCache(10, timedelta(hours=6), clock)
    assert cache.get_or_compute("k", lambda: 1) == (1, True)
    assert cache.get_or_compute("k", lambda: 2) == (1, False)
    clock.now += timedelta(hours=6)
    assert cache.get_or_compute("k", lambda: 3) == (3, True)


def test_the_least_recently_used_entry_is_evicted():
    cache = PredictionCache(2, timedelta(hours=6), Clock())
    cache.get_or_compute("a", lambda: "a")
    cache.get_or_compute("b", lambda: "b")
    cache.get_or_compute("a", lambda: "again")
    cache.get_or_compute("c", lambda: "c")
    assert len(cache) == 2
    assert cache.get_or_compute("a", lambda: "new") == ("a", False)
    assert cache.get_or_compute("b", lambda: "new") == ("new", True)


def test_overlapping_requests_share_one_computation():
    cache = PredictionCache(10, timedelta(hours=6), Clock())
    started, release, calls = threading.Event(), threading.Event(), []

    def compute():
        calls.append(1)
        started.set()
        release.wait(5)
        return "result"

    with ThreadPoolExecutor(4) as pool:
        first = pool.submit(cache.get_or_compute, "k", compute)
        assert started.wait(5)
        followers = [pool.submit(cache.get_or_compute, "k", compute) for _ in range(3)]
        release.set()
        results = [first.result(5)] + [f.result(5) for f in followers]
    assert calls == [1]
    assert results == [("result", True)] + [("result", False)] * 3


def test_a_failed_computation_is_not_cached_and_does_not_leak_to_other_callers():
    cache = PredictionCache(10, timedelta(hours=6), Clock())
    started, release = threading.Event(), threading.Event()

    def failing():
        started.set()
        release.wait(5)
        raise RuntimeError("model failed")

    with ThreadPoolExecutor(2) as pool:
        owner = pool.submit(cache.get_or_compute, "k", failing)
        assert started.wait(5)
        waiter = pool.submit(shared_result, cache)
        threading.Timer(0.1, release.set).start()
        with pytest.raises(RuntimeError):
            owner.result(5)
        assert waiter.result(5) in {"shared failure", "own result"}
    assert cache.get_or_compute("k", lambda: "retry") == ("retry", True)


def shared_result(cache):
    try:
        return cache.get_or_compute("k", lambda: "own result")[0]
    except SharedComputationFailed:
        return "shared failure"
