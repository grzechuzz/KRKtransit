import csv
import gzip
import json
import shutil
from pathlib import Path

import pytest

from krktransit_prediction.adapter.bundle import BundleError, load_bundle
from krktransit_prediction.domain.feature_row import FEATURE_NAMES

BUNDLE = Path(__file__).resolve().parents[1] / "bundle"
CATEGORICAL = frozenset(json.loads((BUNDLE / "manifest.json").read_text())["categorical_features"])


@pytest.fixture(scope="module")
def bundle():
    return load_bundle(BUNDLE)


def golden_rows():
    with gzip.open(BUNDLE / "golden_rows.csv.gz", "rt", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def feature_value(name: str, text: str):
    if text == "":
        return None
    return text if name in CATEGORICAL else float(text)


def test_the_committed_bundle_reproduces_every_golden_row_exactly(bundle):
    rows = golden_rows()
    gains = bundle.model.predict_gains([{name: feature_value(name, row[name]) for name in FEATURE_NAMES}
                                        for row in rows])
    assert len(rows) == 2000
    assert gains == [float(row["expected_gain_sec"]) for row in rows]


def test_single_rows_and_unknown_categories_are_encoded_like_training(bundle):
    row = {name: feature_value(name, golden_rows()[0][name]) for name in FEATURE_NAMES}
    assert bundle.model.predict_gains([row]) == [float(golden_rows()[0]["expected_gain_sec"])]
    encoded = bundle.model.encode([{**row, "current_stop_key": "never seen|99", "prev_5_stop_key": None}])
    assert encoded[0, FEATURE_NAMES.index("current_stop_key")] == 1
    assert encoded[0, FEATURE_NAMES.index("prev_5_stop_key")] == 0


def test_the_bundle_exposes_its_scope_and_context(bundle):
    assert bundle.version == "lightgbm-v5-20261026"
    assert bundle.line_scope == frozenset({"139", "163", "179", "304", "578"})
    assert bundle.context.horizons == (1, 2, 3, 4, 5)
    assert bundle.context.history_lag_seconds == 30
    assert len(bundle.context.profile) == 92842


def test_a_tampered_bundle_is_rejected(tmp_path):
    copy = tmp_path / "bundle"
    shutil.copytree(BUNDLE, copy)
    with (copy / "holidays.json").open("a") as handle:
        handle.write(" ")
    with pytest.raises(BundleError, match="Checksum differs for holidays.json"):
        load_bundle(copy)
    with pytest.raises(BundleError):
        load_bundle(tmp_path / "missing")
