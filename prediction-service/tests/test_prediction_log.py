import csv
from datetime import datetime, timezone

from krktransit_prediction.adapter.prediction_log import CsvPredictionLog
from krktransit_prediction.domain.model_types import AnchorPredictions, Prediction
from prediction_support import TRIP


def predictions(at: datetime, estimated: bool = False) -> AnchorPredictions:
    return AnchorPredictions(TRIP, 3, estimated, (Prediction(4, 72), Prediction(5, -5)), at, "test-model")


def read(path):
    with path.open(newline="") as handle:
        return list(csv.reader(handle))


def test_rows_are_appended_to_daily_files_by_the_utc_prediction_date(tmp_path):
    log = CsvPredictionLog(tmp_path / "predictions")
    log.record(predictions(datetime(2026, 11, 2, 23, 59, 59, tzinfo=timezone.utc)))
    log.record(predictions(datetime(2026, 11, 2, 23, 59, 59, 500000, tzinfo=timezone.utc), estimated=True))
    log.record(predictions(datetime(2026, 11, 3, 0, 0, 1, tzinfo=timezone.utc)))
    first = read(tmp_path / "predictions/predictions-2026-11-02.csv")
    assert first[0] == ["trip_id", "service_date", "target_sequence", "anchor_sequence", "predicted_delay_sec",
                        "anchor_estimated", "predicted_at", "model_version"]
    assert first[1] == ["krakow:mpk-bus:trip", "2026-10-22", "4", "3", "72", "false",
                        "2026-11-02T23:59:59+00:00", "test-model"]
    assert len(first) == 5 and first[3][5] == "true"
    assert len(read(tmp_path / "predictions/predictions-2026-11-03.csv")) == 3


def test_write_failures_do_not_break_predictions(tmp_path):
    blocker = tmp_path / "file"
    blocker.write_text("not a directory")
    CsvPredictionLog(blocker / "predictions").record(predictions(datetime(2026, 11, 2, tzinfo=timezone.utc)))
