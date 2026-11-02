import math
from dataclasses import replace
from datetime import timedelta

from krktransit_prediction.domain.segment_features import (
    delay_observations, history_cutoff, recent_delays, recent_segment, segment_traversals,
)
from krktransit_prediction.domain.model_types import TripKey
from prediction_support import SERVICE_DATE, event, history

HORIZONS = (1, 2, 3, 4, 5)
ANCHOR = event(4, minute=100)


def other(name: str, departure_minute: float, gain: int, direction: int = 0, observed: bool = True):
    trip = TripKey(name, SERVICE_DATE)
    start = event(4, delay=10, minute=departure_minute, trip=trip, observed=observed)
    end = event(5, delay=10 + gain, minute=departure_minute + 2, trip=trip, observed=observed)
    return history([start, end], direction=direction, trip=trip)


def stats(trips):
    traversals = segment_traversals(trips, 0, "stop 4|01", "stop 5|01", HORIZONS)
    return recent_segment(traversals, history_cutoff(ANCHOR, 30))


def test_windows_of_four_and_eight_use_the_most_recent_traversals_by_target_time():
    trips = [other(f"t{i}", 10 + i, gain) for i, gain in enumerate([5, 1, 9, 3, 7, 2, 8, 4, 6, 100])]
    result = stats(trips)
    assert result.gain_last_1 == 100.0
    assert result.gain_mean_4 == (4 + 6 + 100 + 8) / 4
    assert result.gain_median_4 == 7.0
    assert result.gain_mean_8 == sum([9, 3, 7, 2, 8, 4, 6, 100]) / 8
    assert result.gain_median_8 == 6.5


def test_the_thirty_second_availability_lag_is_inclusive():
    trip = other("boundary", 0, 11)
    start, end = trip.events[4], trip.events[5]
    on_boundary = history([start, replace(end, event_time=ANCHOR.event_time - timedelta(seconds=30))], trip=trip.trip)
    too_late = history([start, replace(end, event_time=ANCHOR.event_time - timedelta(seconds=29))], trip=trip.trip)
    assert stats([on_boundary]).gain_last_1 == 11.0
    assert math.isnan(stats([too_late]).gain_last_1)


def test_only_observed_pairs_of_the_same_direction_count_and_implausible_pairs_are_skipped():
    trips = [other("estimated", 10, 50, observed=False), other("opposite", 11, 60, direction=1),
             other("reversed", 12, 70), other("ok", 13, 3)]
    reversed_end = replace(trips[2].events[5], event_time=trips[2].events[4].event_time)
    trips[2] = history([trips[2].events[4], reversed_end], trip=trips[2].trip)
    extreme = other("extreme", 14, 3601)
    assert stats(trips + [extreme]).gain_last_1 == 3.0
    assert stats(trips + [extreme]).gain_mean_8 == 3.0


def test_history_after_a_large_delay_drop_is_kept():
    trip = TripKey("recovering", SERVICE_DATE)
    drop = history([event(2, delay=600, minute=10, trip=trip), event(3, delay=100, minute=12, trip=trip),
                    event(4, delay=100, minute=14, trip=trip), event(5, delay=130, minute=16, trip=trip)], trip=trip)
    assert stats([drop]).gain_last_1 == 30.0


def test_recent_delays_use_observed_events_up_to_max_minus_two_before_the_cutoff():
    trips = []
    for index, delay in enumerate([10, 20, 30, 40, 50]):
        trip = TripKey(f"d{index}", SERVICE_DATE)
        trips.append(history([event(4, delay=delay, minute=50 + index, trip=trip)], trip=trip))
    late_trip = TripKey("late", SERVICE_DATE)
    trips.append(history([event(4, delay=999, minute=99.6, trip=late_trip)], trip=late_trip))
    near_end = TripKey("near-end", SERVICE_DATE)
    trips.append(history([event(4, delay=888, minute=60, trip=near_end, max_sequence=5)], trip=near_end))
    result = recent_delays(delay_observations(trips, 0, "stop 4|01"), history_cutoff(ANCHOR, 30))
    assert result.mean_4 == (20 + 30 + 40 + 50) / 4
    assert result.mean_8 == (10 + 20 + 30 + 40 + 50) / 5
    empty = recent_delays([], history_cutoff(ANCHOR, 30))
    assert math.isnan(empty.mean_4) and math.isnan(empty.mean_8)
