from __future__ import annotations

from collections.abc import Mapping, Sequence

UNKNOWN_DIRECTION = -1
MINIMUM_MATCHED_STOPS = 2


class CanonicalDirections:
    def __init__(self, routes: Mapping[str, tuple[tuple[str, ...], tuple[str, ...]]]) -> None:
        self._routes = dict(routes)

    def lines(self) -> frozenset[str]:
        return frozenset(self._routes)

    def assign(self, line_number: str, stops: Sequence[str]) -> int:
        pair = self._routes.get(line_number)
        if pair is None:
            return UNKNOWN_DIRECTION
        return assign_direction(tuple(stops), pair)


def assign_direction(stops: tuple[str, ...], pair: tuple[tuple[str, ...], tuple[str, ...]]) -> int:
    first, second = sequence_score(stops, pair[0]), sequence_score(stops, pair[1])
    if first == second:
        return UNKNOWN_DIRECTION
    return 0 if first > second else 1


def sequence_score(sequence: tuple[str, ...], canonical: tuple[str, ...]) -> float:
    positions = {stop: index for index, stop in enumerate(canonical)}
    matched = [positions[stop] for stop in sequence if stop in positions]
    if len(matched) < MINIMUM_MATCHED_STOPS:
        return 0.0
    positive = sum(right > left for left, right in zip(matched, matched[1:]))
    negative = sum(right < left for left, right in zip(matched, matched[1:]))
    if positive + negative == 0:
        return 0.0
    return ((positive - negative) / (positive + negative)) * min(1.0, len(matched) / len(sequence))
