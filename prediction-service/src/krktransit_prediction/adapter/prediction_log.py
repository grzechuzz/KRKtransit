from __future__ import annotations

import csv
import logging
import threading
from pathlib import Path

from krktransit_prediction.domain.model_types import AnchorPredictions

LOG = logging.getLogger(__name__)
HEADER = ("trip_id", "service_date", "target_sequence", "anchor_sequence", "predicted_delay_sec",
          "anchor_estimated", "predicted_at", "model_version")


class CsvPredictionLog:
    def __init__(self, directory: Path) -> None:
        self._directory = directory
        self._lock = threading.Lock()

    def record(self, predictions: AnchorPredictions) -> None:
        if not predictions.predictions:
            return
        try:
            with self._lock:
                self._append(predictions)
        except OSError as failure:
            LOG.error("Could not write prediction log: %s", failure)

    def _append(self, predictions: AnchorPredictions) -> None:
        self._directory.mkdir(parents=True, exist_ok=True)
        path = self._directory / f"predictions-{predictions.predicted_at.date().isoformat()}.csv"
        new_file = not path.exists()
        with path.open("a", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            if new_file:
                writer.writerow(HEADER)
            writer.writerows(rows(predictions))
            handle.flush()


def rows(predictions: AnchorPredictions) -> list[tuple[object, ...]]:
    return [(predictions.trip.trip_id, predictions.trip.service_date.isoformat(), prediction.target_sequence,
             predictions.anchor_sequence, prediction.predicted_delay_seconds, str(predictions.anchor_estimated).lower(),
             predictions.predicted_at.isoformat(), predictions.model_version)
            for prediction in predictions.predictions]
