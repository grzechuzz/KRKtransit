from __future__ import annotations

import re
from functools import lru_cache

WHITESPACE = re.compile(r"\s+", re.ASCII)
KEY_SEPARATOR = "|"
CACHED_KEYS = 4096


@lru_cache(maxsize=CACHED_KEYS)
def stop_key(name: str | None, description: str | None) -> str:
    return normalize(name) + KEY_SEPARATOR + normalize(description)


def normalize(value: str | None) -> str:
    return WHITESPACE.sub(" ", (value or "").strip(" ")).lower()
