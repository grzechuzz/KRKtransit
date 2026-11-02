from __future__ import annotations

import hmac
import logging
from collections.abc import Callable
from concurrent import futures
from datetime import date, datetime

import grpc
from google.protobuf.timestamp_pb2 import Timestamp
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

from krktransit.prediction.v1 import prediction_service_pb2 as messages
from krktransit.prediction.v1 import prediction_service_pb2_grpc as services
from krktransit_prediction.application import predict_arrivals
from krktransit_prediction.application.predict_arrivals import PredictionOutcome
from krktransit_prediction.application.prediction_stats import EmptyReason, PredictionStats
from krktransit_prediction.domain.model_types import TripRequest, TripStop
from krktransit_prediction.domain.trip_request import InvalidTripRequest, trip_request

LOG = logging.getLogger(__name__)
SERVICE_NAME = "krktransit.prediction.v1.PredictionService"
HEALTH_METHOD_PREFIX = "/grpc.health.v1.Health/"
REQUEST_WORKERS = 16


class PredictionServicer(services.PredictionServiceServicer):
    def __init__(self, use_case: predict_arrivals.PredictArrivals, stats: PredictionStats, model_version: str) -> None:
        self._use_case = use_case
        self._stats = stats
        self._model_version = model_version

    def PredictArrivals(self, request: messages.PredictArrivalsRequest,
                        context: grpc.ServicerContext) -> messages.PredictArrivalsResponse:
        try:
            trip = domain_request(request)
        except InvalidTripRequest as failure:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(failure))
        try:
            return response(self._use_case.predict(trip))
        except Exception:
            LOG.exception("Prediction failed for trip %s", trip.trip.trip_id)
            self._stats.empty(EmptyReason.FAILED)
            return messages.PredictArrivalsResponse(model_version=self._model_version)


def domain_request(request: messages.PredictArrivalsRequest) -> TripRequest:
    require_fields(request, ("city_id", "trip_id", "service_date", "line_number", "max_stop_sequence"))
    return trip_request(request.city_id, request.trip_id, service_date(request.service_date), request.line_number,
                        request.max_stop_sequence, [trip_stop(stop) for stop in request.stops],
                        list(request.target_sequences))


def require_fields(message, names: tuple[str, ...]) -> None:
    missing = [name for name in names if not message.HasField(name)]
    if missing:
        raise InvalidTripRequest(f"Missing required fields: {', '.join(missing)}")


def service_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as failure:
        raise InvalidTripRequest("service_date must use YYYY-MM-DD") from failure


def trip_stop(stop: messages.TripStop) -> TripStop:
    require_fields(stop, ("stop_sequence", "stop_name", "arrival_seconds"))
    return TripStop(stop.stop_sequence, stop.stop_name, stop.stop_desc if stop.HasField("stop_desc") else None,
                    stop.arrival_seconds)


def response(outcome: PredictionOutcome) -> messages.PredictArrivalsResponse:
    message = messages.PredictArrivalsResponse(model_version=outcome.model_version)
    if outcome.predictions is None:
        return message
    message.anchor_sequence = outcome.predictions.anchor_sequence
    message.anchor_estimated = outcome.predictions.anchor_estimated
    message.predicted_at.CopyFrom(timestamp(outcome.predictions.predicted_at))
    for sequence, delay in sorted(outcome.delays.items()):
        message.predictions.add(stop_sequence=sequence, predicted_delay_seconds=delay)
    return message


def timestamp(time: datetime) -> Timestamp:
    message = Timestamp()
    message.FromDatetime(time)
    return message


class BearerTokenInterceptor(grpc.ServerInterceptor):
    def __init__(self, token: str) -> None:
        self._expected = f"Bearer {token}".encode()
        self._reject = grpc.unary_unary_rpc_method_handler(unauthenticated)

    def intercept_service(self, continuation: Callable, details: grpc.HandlerCallDetails):
        if details.method.startswith(HEALTH_METHOD_PREFIX) or self._authorized(details):
            return continuation(details)
        return self._reject

    def _authorized(self, details: grpc.HandlerCallDetails) -> bool:
        values = [value for key, value in details.invocation_metadata or () if key == "authorization"]
        return len(values) == 1 and hmac.compare_digest(values[0].encode(), self._expected)


def unauthenticated(request, context: grpc.ServicerContext):
    context.abort(grpc.StatusCode.UNAUTHENTICATED, "A valid service bearer token is required")


def start_server(address: str, port: int, servicer: PredictionServicer, token: str) -> tuple[grpc.Server, int]:
    interceptors = (BearerTokenInterceptor(token),) if token else ()
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=REQUEST_WORKERS), interceptors=interceptors)
    services.add_PredictionServiceServicer_to_server(servicer, server)
    health_pb2_grpc.add_HealthServicer_to_server(serving_health(), server)
    bound = server.add_insecure_port(f"[{address}]:{port}" if ":" in address else f"{address}:{port}")
    server.start()
    return server, bound


def serving_health() -> health.HealthServicer:
    servicer = health.HealthServicer()
    servicer.set("", health_pb2.HealthCheckResponse.SERVING)
    servicer.set(SERVICE_NAME, health_pb2.HealthCheckResponse.SERVING)
    return servicer
