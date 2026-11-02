from krktransit_prediction.domain.anchor import select_anchor, supported_targets
from prediction_support import event

HORIZONS = (1, 2, 3, 4, 5)


def test_no_anchor_at_the_first_stop_or_without_events():
    assert select_anchor({}, 10) is None
    assert select_anchor({1: event(1)}, 10) is None


def test_the_highest_sequence_between_two_and_max_minus_one_is_the_anchor():
    events = {2: event(2), 4: event(4), 3: event(3)}
    assert select_anchor(events, 10).sequence == 4
    assert select_anchor({2: event(2), 10: event(10)}, 10).sequence == 2


def test_an_estimated_event_may_be_the_anchor():
    assert not select_anchor({2: event(2), 3: event(3, observed=False)}, 10).observed


def test_targets_stay_within_five_stops_and_exclude_the_terminal():
    assert supported_targets(2, 20, HORIZONS) == (3, 4, 5, 6, 7)
    assert supported_targets(7, 10, HORIZONS) == (8, 9)
    assert supported_targets(9, 10, HORIZONS) == ()
