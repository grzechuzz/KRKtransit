from __future__ import annotations

import logging
import os
import signal
import sys
import threading
from datetime import datetime, timedelta, timezone

import grpc

from krktransit_prediction.adapter.background import PeriodicTask, ProjectionRefresher, SummaryReporter
from krktransit_prediction.adapter.bundle import BundleError, ModelBundle, load_bundle
from krktransit_prediction.adapter.grpc_server import PredictionServicer, start_server
from krktransit_prediction.adapter.prediction_log import CsvPredictionLog
from krktransit_prediction.adapter.stop_events_client import GrpcLineEventSource
from krktransit_prediction.application.event_projection import EventProjection
from krktransit_prediction.application.event_sync import LineEventSync, SyncSettings
from krktransit_prediction.application.predict_arrivals import PredictArrivals, PredictionScope
from krktransit_prediction.application.prediction_cache import PredictionCache
from krktransit_prediction.application.prediction_stats import PredictionStats
from krktransit_prediction.config import ConfigurationError, Settings

LOG = logging.getLogger("krktransit_prediction")
RETENTION = timedelta(days=8)
READ_OVERLAP = timedelta(minutes=2)
PAGE_SIZE = 2_000
STOP_EVENTS_DEADLINE = timedelta(seconds=10)
CACHE_ENTRIES = 5_000
CACHE_TTL = timedelta(hours=6)
SUMMARY_INTERVAL = timedelta(minutes=1)
SHUTDOWN_GRACE_SECONDS = 10


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def main() -> int:
    try:
        settings = Settings.from_environment(os.environ)
    except ConfigurationError as failure:
        logging.basicConfig(level=logging.ERROR)
        LOG.error("Invalid configuration: %s", failure)
        return 1
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        bundle = load_bundle(settings.bundle_directory)
    except BundleError as failure:
        LOG.error("%s", failure)
        return 1
    LOG.info("Loaded model %s (model.txt SHA-256 %s) for lines %s", bundle.version, bundle.model_sha256,
             ", ".join(sorted(bundle.line_scope)))
    run(settings, bundle)
    return 0


def run(settings: Settings, bundle: ModelBundle) -> None:
    channel = grpc.insecure_channel(settings.stop_events_address)
    stats = PredictionStats()
    projection = EventProjection(bundle.context.directions, RETENTION)
    source = GrpcLineEventSource(channel, settings.city_id, settings.stop_events_token, STOP_EVENTS_DEADLINE)
    sync = LineEventSync(source, projection, utc_now, SyncSettings(settings.backfill, READ_OVERLAP, PAGE_SIZE))
    refresher = ProjectionRefresher(sync, projection, bundle.line_scope, stats, utc_now)
    refresher.refresh_all()
    use_case = PredictArrivals(projection, bundle.model, bundle.context,
                               PredictionScope(settings.city_id, bundle.line_scope),
                               PredictionCache(CACHE_ENTRIES, CACHE_TTL, utc_now),
                               CsvPredictionLog(settings.log_directory), stats, utc_now)
    server, port = start_server(settings.grpc_address, settings.grpc_port,
                                PredictionServicer(use_case, stats, bundle.version), settings.grpc_token)
    LOG.info("Prediction gRPC server listening on %s:%d", settings.grpc_address, port)
    tasks = (PeriodicTask("projection-refresh", settings.poll_interval, refresher.refresh_all),
             PeriodicTask("summary", SUMMARY_INTERVAL,
                          SummaryReporter(stats, refresher, utc_now).report))
    for task in tasks:
        task.start()
    wait_for_termination()
    LOG.info("Stopping prediction service")
    server.stop(SHUTDOWN_GRACE_SECONDS).wait()
    for task in tasks:
        task.stop()
    channel.close()


def wait_for_termination() -> None:
    stopped = threading.Event()
    for signal_number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signal_number, lambda *_: stopped.set())
    stopped.wait()


if __name__ == "__main__":
    sys.exit(main())
