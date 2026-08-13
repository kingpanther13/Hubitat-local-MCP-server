"""Secret-safe HTTP timing helpers for the live official-SDK conformance run."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Awaitable, Callable
from typing import Any

try:
    from httpx2 import RequestNotRead
except ModuleNotFoundError as exc:
    if exc.name != "httpx2":
        raise
    # Keep the pure helper-test lane runnable without the live SDK closure installed.
    # The live conformance script imports httpx2 before importing this module, so its
    # real exception class is always used for request hooks.
    class RequestNotRead(Exception):
        pass

MODERN_PROTOCOL_VERSION = "2026-07-28"
DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS = 10
RELAY_LEG_CEILING_SECONDS = 9.5
MINIMUM_LOGICAL_SECONDS = 10.0


def extract_bps_acknowledgment_key(guide: Any) -> str:
    """Extract only the key published on the best-practice guide's canonical line."""
    assert isinstance(guide, str), "best-practice guide content is not text"
    match = re.search(
        r"^Acknowledgment key:\s*(bps-ack-[A-Za-z0-9._-]+)\s*$",
        guide,
        flags=re.MULTILINE,
    )
    assert match, "best-practice guide did not publish an acknowledgment key"
    return match.group(1)


def _indexed_settings(settings: dict[str, Any], prefix: str) -> dict[int, Any]:
    pattern = re.compile(rf"^{re.escape(prefix)}(\d+)$")
    indexed = {}
    for key, value in settings.items():
        match = pattern.fullmatch(str(key))
        if match:
            indexed[int(match.group(1))] = value
    return indexed


def assert_exact_rule_log_messages(
    config: Any,
    expected_messages: list[str],
    *,
    operation: str,
) -> list[str]:
    """Assert an empty RM fixture persisted exactly six requested log actions."""
    assert isinstance(config, dict), f"{operation} returned no config object"
    assert config.get("success") is not False, (
        f"{operation} failed: {config.get('error')!r}"
    )
    settings = config.get("settings")
    assert isinstance(settings, dict), (
        f"{operation} did not return raw settings; includeSettings=true is required"
    )
    assert len(expected_messages) == 6 and len(set(expected_messages)) == 6, (
        "the MRTR proof must request exactly 6 distinct log messages"
    )

    action_types = _indexed_settings(settings, "actType.")
    action_subtypes = _indexed_settings(settings, "actSubType.")
    log_messages = _indexed_settings(settings, "logmsg.")
    assert len(action_types) == 6, (
        f"{operation} must contain exactly 6 action rows; saw indices "
        f"{sorted(action_types)}"
    )
    action_indices = set(action_types)
    assert set(action_subtypes) == action_indices, (
        f"{operation} action-subtype indices do not match action rows: "
        f"actions={sorted(action_indices)}, subtypes={sorted(action_subtypes)}"
    )
    assert set(log_messages) == action_indices, (
        f"{operation} log-message indices do not match action rows: "
        f"actions={sorted(action_indices)}, messages={sorted(log_messages)}"
    )
    assert all(action_types[index] == "messageActs" for index in action_indices), (
        f"{operation} contains a non-log action type: {action_types}"
    )
    assert all(action_subtypes[index] == "getLogMsg" for index in action_indices), (
        f"{operation} contains a non-log action subtype: {action_subtypes}"
    )

    actual = [log_messages[index] for index in sorted(action_indices)]
    assert len(set(actual)) == 6, (
        f"{operation} log messages are not distinct: {actual}"
    )
    assert actual == expected_messages, (
        f"{operation} did not persist the exact messages: "
        f"expected={expected_messages}, actual={actual}"
    )
    return actual


async def find_exact_fixture_id_with_settle(
    list_rules: Callable[[], Awaitable[list[dict[str, Any]]]],
    fixture_name: str,
    *,
    attempts: int = 6,
    delay_seconds: float = 2.0,
    sleep: Callable[[float], Awaitable[None]],
) -> str | None:
    """Boundedly settle a lost create and adopt at most one exact-name rule."""
    assert attempts >= 1, "cleanup lookup attempts must be positive"
    for attempt in range(attempts):
        try:
            rules = await list_rules()
        except Exception:
            if attempt == attempts - 1:
                raise
        else:
            assert isinstance(rules, list), "fixture cleanup rule listing is not a list"
            matches = [
                rule for rule in rules
                if isinstance(rule, dict)
                and (rule.get("name") == fixture_name or rule.get("label") == fixture_name)
            ]
            assert len(matches) <= 1, (
                f"refusing ambiguous cleanup: {len(matches)} rules exactly match "
                f"{fixture_name!r}"
            )
            if matches:
                target_id = str(matches[0].get("id") or matches[0].get("appId") or "")
                assert target_id, (
                    f"exact cleanup match for {fixture_name!r} has no id/appId"
                )
                return target_id
        if attempt < attempts - 1:
            await sleep(delay_seconds)
    return None


async def cleanup_preserving_primary(
    cleanup: Callable[[], Awaitable[None]],
    primary_error: BaseException | None,
) -> Exception | None:
    """Run strict cleanup without replacing an already-propagating failure."""
    try:
        await cleanup()
    except Exception as cleanup_error:
        if primary_error is None:
            raise
        return cleanup_error
    return None


class RequestTrace:
    """Observe request timing and MCP routing headers without retaining secrets."""

    TRACE_ID_EXTENSION = "hubitat_mrtr_trace_id"

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._next_trace_id = 1
        self._pending: dict[int, int] = {}
        self.legs: list[dict[str, Any]] = []
        self.capacity_recovery_boundary = 0

    @staticmethod
    def _fields(request: Any) -> dict[str, Any]:
        has_request_state: bool | None = False
        if request.headers.get("mcp-method") == "tools/call":
            has_request_state = None
            try:
                content = request.content
            except RequestNotRead:
                content = None
            if content is not None:
                try:
                    body = json.loads(content)
                except (AttributeError, TypeError, ValueError):
                    body = None
                if isinstance(body, dict):
                    params = body.get("params")
                    has_request_state = (
                        isinstance(params, dict)
                        and isinstance(params.get("requestState"), str)
                        and bool(params["requestState"])
                    )
        return {
            "method": request.method,
            "mcp_protocol_version": request.headers.get("mcp-protocol-version"),
            "mcp_method": request.headers.get("mcp-method"),
            "mcp_name": request.headers.get("mcp-name"),
            "has_request_state": has_request_state,
        }

    async def record_request(self, request: Any) -> None:
        leg = {**self._fields(request), "status": None, "started": self._clock(),
               "ended": None, "duration": None}
        trace_id = self._next_trace_id
        self._next_trace_id += 1
        request.extensions[self.TRACE_ID_EXTENSION] = trace_id
        self._pending[trace_id] = len(self.legs)
        self.legs.append(leg)

    async def record_response(self, response: Any) -> None:
        trace_id = response.request.extensions.get(self.TRACE_ID_EXTENSION)
        index = self._pending.pop(trace_id, None)
        if index is None:
            raise AssertionError(
                "observer defect: a response arrived for an unrecorded request leg"
            )
        ended = self._clock()
        leg = self.legs[index]
        leg["status"] = int(response.status_code)
        leg["ended"] = ended
        leg["duration"] = ended - leg["started"]

    def mark(self) -> int:
        return len(self.legs)

    def mark_capacity_recovery(self) -> int:
        """Record that a platform-capacity recovery (app bounce) happened here.

        Legs before this boundary may honestly carry relay 504s or unanswered
        requests caused by the hub's per-app load limiter; the served-status
        contract restarts at the boundary while the header contract stays global.
        Counted in POST-space because consumers index into posts(), which filters
        non-POST legs out.
        """
        self.capacity_recovery_boundary = sum(
            1 for leg in self.legs if leg["method"] == "POST"
        )
        return self.capacity_recovery_boundary

    def tool_call_legs(self, since: int, name: str) -> list[dict[str, Any]]:
        return [dict(leg) for leg in self.legs[since:]
                if leg["method"] == "POST"
                and leg["mcp_method"] == "tools/call"
                and leg["mcp_name"] == name]

    def posts(self) -> list[dict[str, Any]]:
        return [dict(leg) for leg in self.legs if leg["method"] == "POST"]


def summarize_modern_posts(
    posts: list[dict[str, Any]], *, served_since: int = 0,
) -> dict[str, Any]:
    """Validate that every observed SDK POST stayed on the modern HTTP path.

    The header contract (protocol version + mirrored Mcp-* headers) is a client-side
    fact and always holds for EVERY post. The served contract (answered with 2xx) is
    hub-capacity-sensitive: `served_since` (a RequestTrace capacity-recovery boundary)
    excuses legs before a verified platform-limiter bounce, whose 504s/aborts are a
    capacity event, not a protocol verdict. Excused non-2xx legs are still counted
    and returned so the caller can report them loudly.
    """
    assert posts, "the observer recorded no SDK POST"
    wrong_versions = [post for post in posts
                      if post.get("mcp_protocol_version") != MODERN_PROTOCOL_VERSION]
    assert not wrong_versions, (
        f"every SDK POST must use {MODERN_PROTOCOL_VERSION}; saw "
        f"{sorted({post.get('mcp_protocol_version') for post in wrong_versions}, key=str)}"
    )
    missing_methods = [post for post in posts if not post.get("mcp_method")]
    assert not missing_methods, (
        f"every modern SDK POST must carry Mcp-Method; missing on {len(missing_methods)} leg(s)"
    )
    missing_names = [post for post in posts
                     if post.get("mcp_method") in {"tools/call", "resources/read"}
                     and not post.get("mcp_name")]
    assert not missing_names, (
        "modern tools/call and resources/read POSTs must carry Mcp-Name; "
        f"missing on {len(missing_names)} leg(s)"
    )
    assert 0 <= served_since <= len(posts), (
        f"served_since must index into the recorded posts; saw {served_since} of {len(posts)}"
    )
    served = posts[served_since:]
    assert served, "no SDK POST was observed after the capacity-recovery boundary"
    answered = [post for post in served if post.get("status") is not None]
    assert len(answered) == len(served), (
        f"{len(served)} modern POST(s) went out but only {len(answered)} came back"
    )
    statuses = sorted({post["status"] for post in answered})
    rejected = [status for status in statuses
                if not isinstance(status, int) or status not in {200, 202}]
    assert not rejected, f"the server did not serve every modern SDK POST: saw status {rejected}"
    excused = sorted({post.get("status") for post in posts[:served_since]
                      if not isinstance(post.get("status"), int)
                      or post.get("status") not in {200, 202}}, key=str)
    return {"posts": len(posts), "statuses": statuses, "capacity_excused": excused}


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
    state_flags = [leg.get("has_request_state") for leg in legs]
    assert state_flags[0] is False and all(flag is True for flag in state_flags[1:]), (
        "the measured call must begin without requestState and every automatic "
        f"continuation must carry it; saw {state_flags}"
    )
    return {
        "legs": len(legs),
        "continuation_rounds": len(legs) - 1,
        "logical_elapsed": logical_elapsed,
        "max_leg_elapsed": max_duration,
        "sdk_round_limit": DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS,
    }


def assert_mrtr_owner_rounds(server_rounds: Any, continuation_rounds: int) -> int:
    """Validate completed worker slices and return coordination-only rounds."""
    assert isinstance(server_rounds, int) and 1 <= server_rounds < continuation_rounds, (
        "MRTR owner slices must be positive and fewer than client "
        f"continuations: client={continuation_rounds}, server={server_rounds!r}"
    )
    return continuation_rounds - server_rounds
