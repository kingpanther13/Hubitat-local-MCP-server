"""Secret-safe HTTP timing helpers for the live official-SDK conformance run."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

MODERN_PROTOCOL_VERSION = "2026-07-28"
DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS = 10
RELAY_LEG_CEILING_SECONDS = 9.5
MINIMUM_LOGICAL_SECONDS = 10.0


class RequestTrace:
    """Observe request timing and MCP routing headers without retaining secrets."""

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._pending: dict[int, int] = {}
        self.legs: list[dict[str, Any]] = []

    @staticmethod
    def _fields(request: Any) -> dict[str, Any]:
        return {
            "method": request.method,
            "mcp_protocol_version": request.headers.get("mcp-protocol-version"),
            "mcp_method": request.headers.get("mcp-method"),
            "mcp_name": request.headers.get("mcp-name"),
        }

    async def record_request(self, request: Any) -> None:
        leg = {**self._fields(request), "status": None, "started": self._clock(),
               "ended": None, "duration": None}
        self._pending[id(request)] = len(self.legs)
        self.legs.append(leg)

    async def record_response(self, response: Any) -> None:
        index = self._pending.pop(id(response.request))
        ended = self._clock()
        leg = self.legs[index]
        leg["status"] = int(response.status_code)
        leg["ended"] = ended
        leg["duration"] = ended - leg["started"]

    def mark(self) -> int:
        return len(self.legs)

    def tool_call_legs(self, since: int, name: str) -> list[dict[str, Any]]:
        return [dict(leg) for leg in self.legs[since:]
                if leg["method"] == "POST"
                and leg["mcp_method"] == "tools/call"
                and leg["mcp_name"] == name]

    def posts_after_initialize(self) -> list[dict[str, Any]]:
        return [dict(leg) for leg in self.legs
                if leg["method"] == "POST" and leg["mcp_protocol_version"] is not None]


def summarize_mrtr_proof(
    legs: list[dict[str, Any]], logical_elapsed: float,
) -> dict[str, int | float]:
    """Validate and summarize one automatic high-level SDK MRTR call."""
    assert len(legs) >= 3, (
        f"one logical call needs at least 3 HTTP legs to prove multiple continuations; saw {len(legs)}"
    )
    assert logical_elapsed > MINIMUM_LOGICAL_SECONDS, (
        f"logical call must exceed 10 seconds; saw {logical_elapsed:.3f}s"
    )
    wrong_versions = sorted({leg["mcp_protocol_version"] for leg in legs
                             if leg["mcp_protocol_version"] != MODERN_PROTOCOL_VERSION})
    assert not wrong_versions, (
        f"every tools/call leg must use {MODERN_PROTOCOL_VERSION}; saw {wrong_versions}"
    )
    bad_statuses = sorted({leg["status"] for leg in legs
                           if not isinstance(leg["status"], int)
                           or not 200 <= leg["status"] < 300})
    assert not bad_statuses, f"every tools/call leg must return 2xx; saw {bad_statuses}"
    durations = [leg["duration"] for leg in legs]
    assert all(isinstance(duration, int | float) for duration in durations), (
        "every tools/call leg must have a completed response timing"
    )
    max_duration = max(durations)
    assert max_duration < RELAY_LEG_CEILING_SECONDS, (
        f"one tools/call leg reached the {RELAY_LEG_CEILING_SECONDS:.1f}s relay ceiling guard: "
        f"{max_duration:.3f}s"
    )
    return {
        "legs": len(legs),
        "continuation_rounds": len(legs) - 1,
        "logical_elapsed": logical_elapsed,
        "max_leg_elapsed": max_duration,
        "sdk_round_limit": DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS,
    }
