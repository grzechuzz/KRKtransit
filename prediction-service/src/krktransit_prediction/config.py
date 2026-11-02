from __future__ import annotations

import ipaddress
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path

DEFAULT_BUNDLE = Path(__file__).resolve().parents[2] / "bundle"
DURATION = re.compile(r"(\d+)(ms|s|m|h)")
DURATION_UNITS = {"ms": "milliseconds", "s": "seconds", "m": "minutes", "h": "hours"}
LOG_LEVELS = frozenset({"DEBUG", "INFO", "WARNING", "ERROR"})


class ConfigurationError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Settings:
    grpc_address: str
    grpc_port: int
    grpc_token: str
    stop_events_address: str
    stop_events_token: str
    city_id: str
    bundle_directory: Path
    log_directory: Path
    poll_interval: timedelta
    backfill: timedelta
    log_level: str

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> Settings:
        settings = cls(
            environment.get("PREDICTION_GRPC_ADDRESS", "127.0.0.1"),
            port(environment.get("PREDICTION_GRPC_PORT", "9093")),
            environment.get("PREDICTION_GRPC_TOKEN", ""),
            environment.get("PREDICTION_STOP_EVENTS_ADDRESS", "127.0.0.1:9092"),
            environment.get("PREDICTION_STOP_EVENTS_TOKEN", ""),
            environment.get("PREDICTION_CITY_ID", "krakow"),
            Path(environment.get("PREDICTION_BUNDLE_DIRECTORY", str(DEFAULT_BUNDLE))),
            Path(environment.get("PREDICTION_LOG_DIRECTORY", "./data/predictions")),
            duration(environment.get("PREDICTION_POLL_INTERVAL", "10s")),
            timedelta(days=positive_integer(environment.get("PREDICTION_BACKFILL_DAYS", "7"), "PREDICTION_BACKFILL_DAYS")),
            log_level(environment.get("PREDICTION_LOG_LEVEL", "INFO")),
        )
        require_token_beyond_loopback(settings.grpc_address, settings.grpc_token)
        return settings


def port(value: str) -> int:
    number = positive_integer(value, "PREDICTION_GRPC_PORT")
    if number > 65535:
        raise ConfigurationError("PREDICTION_GRPC_PORT must be at most 65535")
    return number


def positive_integer(value: str, name: str) -> int:
    if not value.isdigit() or int(value) < 1:
        raise ConfigurationError(f"{name} must be a positive integer")
    return int(value)


def duration(value: str) -> timedelta:
    match = DURATION.fullmatch(value)
    if match is None or int(match.group(1)) == 0:
        raise ConfigurationError("PREDICTION_POLL_INTERVAL must be a positive duration such as 10s")
    return timedelta(**{DURATION_UNITS[match.group(2)]: int(match.group(1))})


def log_level(value: str) -> str:
    level = value.upper()
    if level not in LOG_LEVELS:
        raise ConfigurationError(f"PREDICTION_LOG_LEVEL must be one of {', '.join(sorted(LOG_LEVELS))}")
    return level


def require_token_beyond_loopback(address: str, token: str) -> None:
    if not token and not loopback(address):
        raise ConfigurationError("PREDICTION_GRPC_TOKEN is required when binding beyond loopback")


def loopback(address: str) -> bool:
    if address == "localhost":
        return True
    try:
        return ipaddress.ip_address(address).is_loopback
    except ValueError:
        return False
