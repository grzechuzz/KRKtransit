from datetime import datetime, timezone

import grpc
import pytest
from grpc_health.v1 import health_pb2, health_pb2_grpc

from krktransit.prediction.v1 import prediction_service_pb2 as messages
from krktransit.prediction.v1 import prediction_service_pb2_grpc as services
from krktransit_prediction.adapter.grpc_server import PredictionServicer, start_server
from krktransit_prediction.application.predict_arrivals import PredictionOutcome
from krktransit_prediction.application.prediction_stats import EmptyReason, PredictionStats
from krktransit_prediction.domain.model_types import AnchorPredictions, Prediction, TripKey

PREDICTED_AT = datetime(2026, 11, 2, 7, 30, tzinfo=timezone.utc)
TOKEN = (("authorization", "Bearer secret"),)


class FakeUseCase:
    def __init__(self):
        self.requests, self.failure = [], None

    def predict(self, request):
        self.requests.append(request)
        if self.failure:
            raise self.failure
        predictions = AnchorPredictions(request.trip, 3, True, (Prediction(4, 72),), PREDICTED_AT, "test-model")
        return PredictionOutcome("test-model", predictions, {4: 72}, None)


def valid_request(**changes) -> messages.PredictArrivalsRequest:
    request = messages.PredictArrivalsRequest(
        city_id="krakow", trip_id="krakow:mpk-bus:t", service_date="2026-11-02", line_number="139",
        max_stop_sequence=3, target_sequences=[2, 3],
        stops=[messages.TripStop(stop_sequence=seq, stop_name=f"S{seq}", arrival_seconds=3600 + 60 * seq)
               for seq in (1, 2, 3)])
    for name, value in changes.items():
        if value is None:
            request.ClearField(name)
        else:
            setattr(request, name, value)
    return request


@pytest.fixture
def server():
    use_case, stats = FakeUseCase(), PredictionStats()
    running, port = start_server("127.0.0.1", 0, PredictionServicer(use_case, stats, "test-model"), "secret")
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    yield use_case, stats, channel
    channel.close()
    running.stop(0)


def call(channel, request, metadata=TOKEN):
    return services.PredictionServiceStub(channel).PredictArrivals(request, timeout=5, metadata=metadata)


def status_of(channel, request, metadata=TOKEN):
    with pytest.raises(grpc.RpcError) as failure:
        call(channel, request, metadata)
    return failure.value.code()


def test_maps_the_request_and_the_predictions(server):
    use_case, _, channel = server
    response = call(channel, valid_request())
    request = use_case.requests[0]
    assert request.trip == TripKey("krakow:mpk-bus:t", datetime(2026, 11, 2).date())
    assert (request.max_sequence, request.target_sequences, request.stop(2).stop_desc) == (3, (2, 3), None)
    assert [(p.stop_sequence, p.predicted_delay_seconds) for p in response.predictions] == [(4, 72)]
    assert (response.model_version, response.anchor_sequence, response.anchor_estimated) == ("test-model", 3, True)
    assert response.predicted_at.ToDatetime(tzinfo=timezone.utc) == PREDICTED_AT


def test_requires_the_bearer_token_but_not_for_health(server):
    _, _, channel = server
    assert status_of(channel, valid_request(), metadata=()) == grpc.StatusCode.UNAUTHENTICATED
    assert status_of(channel, valid_request(), metadata=(("authorization", "Bearer wrong"),)) == grpc.StatusCode.UNAUTHENTICATED
    health = health_pb2_grpc.HealthStub(channel).Check(health_pb2.HealthCheckRequest(
        service="krktransit.prediction.v1.PredictionService"), timeout=5)
    assert health.status == health_pb2.HealthCheckResponse.SERVING


@pytest.mark.parametrize("changes", [
    {"trip_id": None}, {"service_date": "02.11.2026"}, {"max_stop_sequence": 0}, {"line_number": " "},
])
def test_invalid_requests_return_invalid_argument(server, changes):
    use_case, _, channel = server
    assert status_of(channel, valid_request(**changes)) == grpc.StatusCode.INVALID_ARGUMENT
    assert use_case.requests == []


def test_duplicate_or_incomplete_stops_are_invalid(server):
    _, _, channel = server
    duplicate = valid_request()
    duplicate.stops.add(stop_sequence=2, stop_name="again", arrival_seconds=1)
    assert status_of(channel, duplicate) == grpc.StatusCode.INVALID_ARGUMENT
    incomplete = valid_request()
    incomplete.stops.add(stop_sequence=3)
    assert status_of(channel, incomplete) == grpc.StatusCode.INVALID_ARGUMENT


def test_unexpected_failures_return_an_empty_result(server):
    use_case, stats, channel = server
    use_case.failure = RuntimeError("model crashed")
    response = call(channel, valid_request())
    assert list(response.predictions) == [] and response.model_version == "test-model"
    assert not response.HasField("anchor_sequence")
    assert stats.drain().empty == {EmptyReason.FAILED: 1}
