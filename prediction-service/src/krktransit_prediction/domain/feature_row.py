from __future__ import annotations

from dataclasses import dataclass

from krktransit_prediction.domain.calendar import CalendarFeatures
from krktransit_prediction.domain.model_types import StopEvent
from krktransit_prediction.domain.segment_features import RecentDelays, RecentSegment
from krktransit_prediction.domain.segment_profile import ProfileStats
from krktransit_prediction.domain.vehicle_features import OwnTripFeatures, ScheduleFeatures

FEATURE_NAMES = (
    "current_delay_sec", "line_number", "direction_id", "current_stop_sequence", "max_stop_sequence",
    "current_stop_key", "target_horizon", "target_stop_key", "scheduled_travel_time_to_target_sec",
    "prev_2_delay_sec", "prev_3_delay_sec", "prev_4_delay_sec", "prev_5_delay_sec",
    "prev_2_stop_key", "prev_3_stop_key", "prev_4_stop_key", "prev_5_stop_key",
    "current_day_of_week", "is_non_working_day", "current_minute_of_day", "time_sin", "time_cos",
    "delay_delta_1_2_sec", "mean_delay_delta_last_4_sec",
    "next_1_scheduled_travel_time_sec", "next_2_scheduled_travel_time_sec", "next_3_scheduled_travel_time_sec",
    "next_4_scheduled_travel_time_sec", "next_5_scheduled_travel_time_sec",
    "recent_segment_gain_mean_4_sec", "recent_segment_gain_median_4_sec", "recent_segment_gain_mean_8_sec",
    "recent_segment_gain_median_8_sec", "recent_segment_gain_last_1_sec",
    "recent_current_delay_mean_4_sec", "recent_current_delay_mean_8_sec",
    "historical_segment_gain_mean_same_daytype_30m_sec", "historical_segment_gain_median_same_daytype_30m_sec",
    "historical_segment_gain_std_same_daytype_30m_sec", "historical_segment_gain_count_same_daytype_30m",
)

FeatureValue = str | int | float | None


@dataclass(frozen=True, slots=True)
class TargetInputs:
    sequence: int
    key: str
    schedule: ScheduleFeatures
    recent_segment: RecentSegment
    profile: ProfileStats


@dataclass(frozen=True, slots=True)
class AnchorInputs:
    line_number: str
    direction: int
    max_sequence: int
    anchor: StopEvent
    own_trip: OwnTripFeatures
    calendar: CalendarFeatures
    recent_delays: RecentDelays


def feature_row(anchor: AnchorInputs, target: TargetInputs) -> dict[str, FeatureValue]:
    values = {
        **identity_values(anchor, target),
        **own_trip_values(anchor.own_trip),
        **calendar_values(anchor.calendar),
        **schedule_values(target),
        **history_values(anchor.recent_delays, target),
    }
    return {name: values[name] for name in FEATURE_NAMES}


def identity_values(anchor: AnchorInputs, target: TargetInputs) -> dict[str, FeatureValue]:
    return {
        "current_delay_sec": anchor.own_trip.current_delay,
        "line_number": anchor.line_number,
        "direction_id": anchor.direction,
        "current_stop_sequence": anchor.anchor.sequence,
        "max_stop_sequence": anchor.max_sequence,
        "current_stop_key": anchor.anchor.key,
        "target_horizon": target.sequence - anchor.anchor.sequence,
        "target_stop_key": target.key,
    }


def own_trip_values(own_trip: OwnTripFeatures) -> dict[str, FeatureValue]:
    values: dict[str, FeatureValue] = {}
    for position, stop in enumerate(own_trip.previous, start=2):
        values[f"prev_{position}_delay_sec"] = stop.delay
        values[f"prev_{position}_stop_key"] = stop.key
    values["delay_delta_1_2_sec"] = own_trip.delay_delta_1_2
    values["mean_delay_delta_last_4_sec"] = own_trip.mean_delay_delta_last_4
    return values


def calendar_values(calendar: CalendarFeatures) -> dict[str, FeatureValue]:
    return {
        "current_day_of_week": str(calendar.day_of_week),
        "is_non_working_day": int(calendar.non_working_day),
        "current_minute_of_day": calendar.minute_of_day,
        "time_sin": calendar.time_sin,
        "time_cos": calendar.time_cos,
    }


def schedule_values(target: TargetInputs) -> dict[str, FeatureValue]:
    values: dict[str, FeatureValue] = {"scheduled_travel_time_to_target_sec": target.schedule.travel_time_to_target}
    for step, duration in enumerate(target.schedule.next_segments, start=1):
        values[f"next_{step}_scheduled_travel_time_sec"] = duration
    return values


def history_values(delays: RecentDelays, target: TargetInputs) -> dict[str, FeatureValue]:
    recent, profile = target.recent_segment, target.profile
    return {
        "recent_segment_gain_mean_4_sec": recent.gain_mean_4,
        "recent_segment_gain_median_4_sec": recent.gain_median_4,
        "recent_segment_gain_mean_8_sec": recent.gain_mean_8,
        "recent_segment_gain_median_8_sec": recent.gain_median_8,
        "recent_segment_gain_last_1_sec": recent.gain_last_1,
        "recent_current_delay_mean_4_sec": delays.mean_4,
        "recent_current_delay_mean_8_sec": delays.mean_8,
        "historical_segment_gain_mean_same_daytype_30m_sec": profile.mean,
        "historical_segment_gain_median_same_daytype_30m_sec": profile.median,
        "historical_segment_gain_std_same_daytype_30m_sec": profile.std,
        "historical_segment_gain_count_same_daytype_30m": profile.count,
    }
