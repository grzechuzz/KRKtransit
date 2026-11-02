from __future__ import annotations

import importlib
import sys
from pathlib import Path

TESTS = Path(__file__).resolve().parent
SERVICE = TESTS.parent
sys.path.insert(0, str(SERVICE))
sys.path.insert(0, str(TESTS))
importlib.import_module("generate_stubs").generate()
