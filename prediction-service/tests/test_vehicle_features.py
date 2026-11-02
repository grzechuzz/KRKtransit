import math

from krktransit_prediction.domain.model_types import TripStop
from krktransit_prediction.domain.vehicle_features import own_trip_features, schedule_features
from prediction_support import event, request, stops


def test_previous_stops_use_observed_events_from_the_second_stop_on():
    events = {seq: event(seq, delay=10 * seq) for seq in range(1, 7)}
    features = own_trip_features(events[6], events)
    assert [stop.delay for stop in features.previous] == [50.0, 40.0, 30.0, 20.0]
    assert features.previous[0].key == "stop 5|01"
    near_start = own_trip_features(events[3], events)
    assert [stop.delay for stop in near_start.previous[:1]] == [20.0]
    assert all(math.isnan(stop.delay) and stop.key is None for stop in near_start.previous[1:])


def test_estimated_previous_stops_are_missing_like_in_training():
    events = {4: event(4, delay=40, observed=False), 5: event(5, delay=50), 6: event(6, delay=90)}
    features = own_trip_features(events[6], events)
    assert features.previous[0].delay == 50.0
    assert math.isnan(features.previous[1].delay) and features.previous[1].key is None


def test_delay_dynamics_follow_the_training_definition():
    events = {seq: event(seq, delay=delay) for seq, delay in ((2, 0), (3, 30), (5, 60), (6, 50))}
    features = own_trip_features(events[6], events)
    assert features.delay_delta_1_2 == 50 - 60
    assert features.mean_delay_delta_last_4 == ((30 - 0) + (50 - 60)) / 2
    lonely = own_trip_features(events[2], {2: events[2]})
    assert math.isnan(lonely.delay_delta_1_2) and math.isnan(lonely.mean_delay_delta_last_4)


def test_schedule_times_come_from_the_requested_trip_stops():
    trip = request(max_sequence=10, trip_stops=[TripStop(seq, f"S{seq}", None, 1000 + 60 * seq) for seq in range(1, 11)])
    features = schedule_features(trip, 5, 7)
    assert features.travel_time_to_target == 120
    assert features.next_segments[:4] == (60.0, 60.0, 60.0, 60.0)
    assert math.isnan(features.next_segments[4])


def test_missing_or_non_positive_segments_are_missing_and_missing_target_stops_give_no_row():
    trip_stops = [TripStop(seq, f"S{seq}", None, 1000 + 60 * seq) for seq in range(1, 11) if seq != 7]
    trip_stops[2] = TripStop(3, "S3", None, 1000 + 60 * 2)
    trip = request(max_sequence=10, trip_stops=trip_stops)
    features = schedule_features(trip, 2, 4)
    assert math.isnan(features.next_segments[0])
    assert math.isnan(features.next_segments[4])
    assert schedule_features(trip, 5, 7) is None
    assert schedule_features(request(trip_stops=stops()), 5, 11) is None
