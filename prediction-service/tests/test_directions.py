import json
from pathlib import Path

from krktransit_prediction.domain.directions import UNKNOWN_DIRECTION, CanonicalDirections, sequence_score

ROUTES = json.loads((Path(__file__).resolve().parents[1] / "bundle/canonical_directions.json").read_text())
DIRECTIONS = CanonicalDirections({line: (tuple(pair[0]), tuple(pair[1])) for line, pair in ROUTES.items()})


def test_each_canonical_route_is_assigned_its_own_direction():
    for line, (first, second) in ROUTES.items():
        assert DIRECTIONS.assign(line, first) == 0
        assert DIRECTIONS.assign(line, second) == 1


def test_a_partial_route_in_canonical_order_keeps_its_direction():
    for line, (first, second) in ROUTES.items():
        assert DIRECTIONS.assign(line, first[3:9]) == 0
        assert DIRECTIONS.assign(line, second[3:9]) == 1


def test_unknown_lines_and_unmatched_stops_have_no_direction():
    assert DIRECTIONS.assign("999", ROUTES["139"][0]) == UNKNOWN_DIRECTION
    assert DIRECTIONS.assign("139", ("nowhere|01", "elsewhere|02")) == UNKNOWN_DIRECTION
    assert DIRECTIONS.assign("139", ()) == UNKNOWN_DIRECTION


def test_score_matches_the_training_definition():
    canonical = ("a", "b", "c", "d")
    assert sequence_score(("a", "b", "c"), canonical) == 1.0
    assert sequence_score(("c", "b", "a"), canonical) == -1.0
    assert sequence_score(("a", "x", "b", "y"), canonical) == 0.5
    assert sequence_score(("a",), canonical) == 0.0
    assert sequence_score(("a", "c", "b"), canonical) == 0.0
