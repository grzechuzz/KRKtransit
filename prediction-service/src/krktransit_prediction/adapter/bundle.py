from __future__ import annotations

import csv
import gzip
import hashlib
import json
import lzma
import math
import threading
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import lightgbm
import numpy as np

from krktransit_prediction.domain.calendar import Holidays
from krktransit_prediction.domain.directions import CanonicalDirections
from krktransit_prediction.domain.feature_row import FEATURE_NAMES, FeatureValue
from krktransit_prediction.domain.prediction_inputs import ModelContext
from krktransit_prediction.domain.segment_profile import ProfileKey, ProfileStats, SegmentProfile

MANIFEST = "manifest.json"
MISSING_CODE = 0
UNKNOWN_CODE = 1
PREDICTION_THREADS = 1


class BundleError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class ModelBundle:
    version: str
    model_sha256: str
    line_scope: frozenset[str]
    model: LightGbmDelayModel
    context: ModelContext


class LightGbmDelayModel:
    def __init__(self, booster: lightgbm.Booster, manifest: Mapping) -> None:
        self._booster = booster
        self._version = manifest["model_version"]
        self._features = tuple(manifest["features"])
        self._codes = {name: {value: code for code, value in enumerate(values)}
                       for name, values in manifest["categorical_vocabulary"].items()}
        self._float32 = frozenset(manifest["float32_features"])
        self._lock = threading.Lock()

    @property
    def version(self) -> str:
        return self._version

    def encode(self, rows: Sequence[Mapping[str, FeatureValue]]) -> np.ndarray:
        matrix = np.empty((len(rows), len(self._features)), dtype=np.float64)
        for row_index, row in enumerate(rows):
            for column, name in enumerate(self._features):
                matrix[row_index, column] = self._encoded(name, row[name])
        return matrix

    def predict_gains(self, rows: Sequence[Mapping[str, FeatureValue]]) -> list[float]:
        matrix = self.encode(rows)
        with self._lock:
            return [float(value) for value in self._booster.predict(matrix, num_threads=PREDICTION_THREADS)]

    def _encoded(self, name: str, value: FeatureValue) -> float:
        if name in self._codes:
            return float(category_code(self._codes[name], value))
        if missing_number(value):
            return math.nan
        number = float(value)
        return float(np.float32(number)) if name in self._float32 else number


def category_code(codes: Mapping[str, int], value: FeatureValue) -> int:
    if value is None:
        return MISSING_CODE
    return codes.get(str(value), UNKNOWN_CODE)


def missing_number(value: FeatureValue) -> bool:
    return value is None or (isinstance(value, float) and math.isnan(value))


def load_bundle(directory: Path) -> ModelBundle:
    try:
        manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
        verify_checksums(directory, manifest)
        model_text = lzma.decompress((directory / manifest["model"]["file"]).read_bytes())
        require(sha256(model_text) == manifest["model"]["model_txt_sha256"], "Decompressed model checksum differs")
        booster = lightgbm.Booster(model_str=model_text.decode("utf-8"))
        verify_model(booster, manifest)
        return ModelBundle(manifest["model_version"], manifest["model"]["model_txt_sha256"],
                           frozenset(manifest["line_scope"]), LightGbmDelayModel(booster, manifest),
                           model_context(directory, manifest))
    except (OSError, ValueError, KeyError, TypeError, lzma.LZMAError, lightgbm.basic.LightGBMError) as failure:
        raise BundleError(f"Invalid model bundle in {directory}: {failure}") from failure


def verify_checksums(directory: Path, manifest: Mapping) -> None:
    for name, expected in manifest["files"].items():
        require(sha256((directory / name).read_bytes()) == expected, f"Checksum differs for {name}")


def verify_model(booster: lightgbm.Booster, manifest: Mapping) -> None:
    require(tuple(manifest["features"]) == FEATURE_NAMES, "Manifest features differ from the feature builder")
    require(booster.feature_name() == list(FEATURE_NAMES), "Model features differ from the feature builder")
    require(booster.num_trees() == manifest["model"]["trees"], "Model tree count differs from the manifest")


def model_context(directory: Path, manifest: Mapping) -> ModelContext:
    return ModelContext(load_directions(directory / manifest["directions"]["file"]),
                        load_holidays(directory / manifest["holidays"]["file"]),
                        load_profile(directory / "segment_profile.csv.gz", manifest["segment_profile"]["rows"]),
                        tuple(manifest["horizons"]), manifest["history_availability_lag_seconds"])


def load_directions(path: Path) -> CanonicalDirections:
    routes = json.loads(path.read_text(encoding="utf-8"))
    return CanonicalDirections({line: (tuple(pair[0]), tuple(pair[1])) for line, pair in routes.items()})


def load_holidays(path: Path) -> Holidays:
    return Holidays(date.fromisoformat(entry["date"]) for entry in json.loads(path.read_text(encoding="utf-8")))


def load_profile(path: Path, expected_rows: int) -> SegmentProfile:
    with gzip.open(path, "rt", encoding="utf-8", newline="") as handle:
        entries = dict(profile_entry(row) for row in csv.DictReader(handle))
    require(len(entries) == expected_rows, "Segment profile row count differs from the manifest")
    return SegmentProfile(entries)


def profile_entry(row: Mapping[str, str]) -> tuple[ProfileKey, ProfileStats]:
    key = ProfileKey(row["line_number"], int(row["direction_id"]), row["current_stop_key"], row["target_stop_key"],
                     row["is_non_working_day"] == "1", int(row["slot_30m"]))
    stats = ProfileStats(optional_float(row["gain_mean"]), optional_float(row["gain_median"]),
                         optional_float(row["gain_std"]), int(row["gain_count"]))
    return key, stats


def optional_float(value: str) -> float:
    return float(value) if value else math.nan


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)
