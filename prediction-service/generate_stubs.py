from __future__ import annotations

import sys
from importlib import resources
from pathlib import Path

from grpc_tools import protoc

SERVICE = Path(__file__).resolve().parent
REPOSITORY = SERVICE.parent
PROTO_ROOTS = (REPOSITORY / "prediction-contracts/src/main/proto", REPOSITORY / "stop-events-contracts/src/main/proto")
PROTOS = ("krktransit/prediction/v1/prediction_service.proto", "krktransit/stopevents/v1/line_events_service.proto")
OUTPUT = SERVICE / "src"


def generate() -> None:
    include = resources.files("grpc_tools") / "_proto"
    arguments = ["grpc_tools.protoc", f"-I{include}", *(f"-I{root}" for root in PROTO_ROOTS),
                 f"--python_out={OUTPUT}", f"--grpc_python_out={OUTPUT}", *PROTOS]
    if protoc.main(arguments) != 0:
        raise SystemExit("Generating gRPC stubs failed")


if __name__ == "__main__":
    generate()
    sys.exit(0)
