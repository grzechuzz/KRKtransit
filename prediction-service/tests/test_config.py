from datetime import timedelta
from pathlib import Path

import pytest

from krktransit_prediction.config import ConfigurationError, Settings


def test_defaults_match_the_documented_configuration():
    settings = Settings.from_environment({})
    assert (settings.grpc_address, settings.grpc_port, settings.grpc_token) == ("127.0.0.1", 9093, "")
    assert settings.stop_events_address == "127.0.0.1:9092"
    assert settings.bundle_directory == Path(__file__).resolve().parents[1] / "bundle"
    assert settings.log_directory == Path("./data/predictions")
    assert (settings.poll_interval, settings.backfill) == (timedelta(seconds=10), timedelta(days=7))
    assert (settings.city_id, settings.log_level) == ("krakow", "INFO")


def test_values_are_read_from_the_environment():
    settings = Settings.from_environment({"PREDICTION_GRPC_ADDRESS": "0.0.0.0", "PREDICTION_GRPC_TOKEN": "secret",
                                          "PREDICTION_POLL_INTERVAL": "500ms", "PREDICTION_BACKFILL_DAYS": "2",
                                          "PREDICTION_LOG_LEVEL": "debug"})
    assert settings.poll_interval == timedelta(milliseconds=500)
    assert settings.backfill == timedelta(days=2)
    assert settings.log_level == "DEBUG"


@pytest.mark.parametrize("environment", [
    {"PREDICTION_GRPC_ADDRESS": "0.0.0.0"},
    {"PREDICTION_GRPC_PORT": "0"},
    {"PREDICTION_GRPC_PORT": "70000"},
    {"PREDICTION_POLL_INTERVAL": "10"},
    {"PREDICTION_POLL_INTERVAL": "0s"},
    {"PREDICTION_BACKFILL_DAYS": "-1"},
    {"PREDICTION_LOG_LEVEL": "LOUD"},
])
def test_invalid_settings_are_rejected(environment):
    with pytest.raises(ConfigurationError):
        Settings.from_environment(environment)


def test_loopback_addresses_do_not_require_a_token():
    for address in ("127.0.0.1", "::1", "localhost"):
        assert Settings.from_environment({"PREDICTION_GRPC_ADDRESS": address}).grpc_token == ""
