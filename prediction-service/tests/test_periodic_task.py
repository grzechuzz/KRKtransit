import logging
import threading
from datetime import timedelta

from krktransit_prediction.adapter.background import PeriodicTask

INTERVAL = timedelta(milliseconds=1)
WAIT_SECONDS = 5


def test_task_keeps_running_and_logs_after_an_unexpected_failure(caplog):
    calls = []
    recovered = threading.Event()

    def action():
        calls.append(1)
        if len(calls) == 1:
            raise ValueError("bad event")
        recovered.set()

    task = PeriodicTask("projection-refresh", INTERVAL, action)
    with caplog.at_level(logging.ERROR):
        task.start()
        assert recovered.wait(WAIT_SECONDS)
        task.stop()
    assert "Periodic task projection-refresh failed" in caplog.text
    assert "bad event" in caplog.text


def test_stop_ends_a_healthy_task():
    called = threading.Event()
    task = PeriodicTask("summary", INTERVAL, called.set)
    task.start()
    assert called.wait(WAIT_SECONDS)
    task.stop()
    called.clear()
    assert not called.wait(0.05)
