#!/usr/bin/env python3
"""
Hubitat MCP Server — conformance scenarios driven by the OFFICIAL MCP Python SDK.

The hub sandbox whitelists imports and cannot load jars, so this server's protocol layer is
hand-written. That makes an independent referee necessary: here the official `mcp` package's
client speaks Streamable HTTP to a real hub endpoint on the pinned modern protocol,
with every response parsed through the SDK's own validators rather than through assertions
written against this repo's reading of the spec. The companion leg is
`src/test/groovy/server/McpWireSchemaConformanceSpec.groovy`. Read docs/testing.md
§ Conformance harness before changing either.

NO SILENT SKIPS. A missing `mcp` package, a version other than the pin, or missing hub config
all FAIL with a remediation message. A skip here would look like coverage.

Every scenario uses MCP 2026-07-28; this E2E harness never negotiates or exercises legacy mode.
The MRTR scenario creates one uniquely named BAT_E2E_ Rule Machine fixture, proves one high-level
SDK call continues across several cloud-relay requests, and removes only that exact fixture in a
`finally`.

Configuration is shared with tests/e2e_test.py — `tests/e2e_config.json` (gitignored) or
HUBITAT_HUB_URL / HUBITAT_APP_ID / HUBITAT_ACCESS_TOKEN.

Usage:
    pip install -r tests/sdk-conformance-requirements.txt
    pip install requests          # tests/e2e_test.py's dep; imported for the shared config
    python tests/sdk_conformance_test.py
"""

from __future__ import annotations

import json
import logging
import sys
import time
import uuid
from http import HTTPStatus
from pathlib import Path
from typing import Any

_INSTALL_HINT = "pip install -r tests/sdk-conformance-requirements.txt"

try:
    import anyio
    import httpx2
    from mcp.client import Client
    from mcp.client.streamable_http import streamable_http_client
    from mcp_types import CallToolResult
except ImportError as exc:
    # A FAILURE, never a skip: these scenarios are the only thing proving this server
    # interoperates with the reference client, so "not installed" must never read green.
    print(f"ERROR: the official MCP Python SDK is not importable ({exc}).")
    print(f"  This is a FAILURE, not a skip -- install the pinned SDK and re-run:\n    {_INSTALL_HINT}")
    sys.exit(1)

REQUIREMENTS = Path(__file__).resolve().parent / "sdk-conformance-requirements.txt"

from sdk_conformance_helpers import (  # noqa: E402  (import guard above supplies its remediation)
    DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS,
    MODERN_PROTOCOL_VERSION,
    RequestTrace,
    assert_exact_rule_log_messages,
    assert_mrtr_owner_rounds,
    cleanup_preserving_primary,
    extract_bps_acknowledgment_key,
    find_exact_fixture_id_with_settle,
    summarize_modern_posts,
    summarize_mrtr_proof,
)

# HTTP timeouts for the client this file constructs. 90s on the request leg covers a
# cloud-relay round trip for a full tools/list; the read leg keeps the SDK's own default.
REQUEST_TIMEOUT_SECONDS = 90.0
SSE_READ_TIMEOUT_SECONDS = 300.0


def pinned_sdk_version() -> str:
    """The `mcp` version pinned in sdk-conformance-requirements.txt — the single source of
    truth shared with hub-e2e.yml's install step (which installs that same file)."""
    if not REQUIREMENTS.exists():
        raise RuntimeError(f"Missing {REQUIREMENTS} -- the SDK pin lives there.")
    for raw in REQUIREMENTS.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line.startswith("mcp=="):
            return line.split("==", 1)[1].strip()
    raise RuntimeError(f"No `mcp==<version>` pin found in {REQUIREMENTS}.")


def deprecated_sdk_usages() -> list[str]:
    """Unexpected deprecations on SDK entry points this file calls.

    Read from the callables' own `__deprecated__` markers (`typing_extensions.deprecated`
    records its message there) rather than a hand-maintained list, so a pin bump that
    deprecates something used here fails the run and names the replacement.

    Deprecated `@overload`s are invisible here -- the decorator sits on the overload, not the
    implementation -- but `list_tools()` is called with no arguments, which binds to the
    undecorated no-arg overload, not the deprecated positional-`cursor` one.
    """
    checked = [("streamable_http_client", streamable_http_client)] + [
        (f"Client.{name}", getattr(Client, name))
        for name in ("list_tools", "call_tool", "list_resources", "read_resource",
                     "list_resource_templates")
    ]
    return [
        f"{label}: {fn.__deprecated__}"
        for label, fn in checked
        if getattr(fn, "__deprecated__", None)
    ]


def _http_failure_detail(exc: httpx2.HTTPError, endpoint: str) -> str:
    """Describe an httpx2 failure from STRUCTURED FIELDS ONLY -- never from `str(exc)`.

    httpx2 quotes the full request URL in every URL-bearing exception message and this endpoint
    authenticates by a query token, so `str(exc)` carries a live credential. Do NOT replace this
    with a scrubber: a mask that slices the token still discloses part of it, which is what
    CodeQL's py/clear-text-logging-sensitive-data flagged on the earlier `_redact` attempt.
    Diagnosability is preserved deliberately -- a 401 still reads as an unmistakable 401.
    """
    parts = [type(exc).__name__]
    response = getattr(exc, "response", None)
    if response is not None:
        status = int(response.status_code)
        try:
            phrase = HTTPStatus(status).phrase
        except ValueError:
            phrase = ""
        parts.append(f"HTTP {status} {phrase}".rstrip())
    request = getattr(exc, "request", None)
    if request is not None:
        parts.append(f"on {request.method}")
    parts.append(f"to {endpoint}")
    return " ".join(parts)


def _failure_detail(exc: BaseException, endpoint: str) -> str:
    """One actionable line for any failure, flattening anyio's ExceptionGroup wrappers.

    A transport failure surfaces as "unhandled errors in a TaskGroup", which names nothing
    actionable -- the real cause is a sub-exception, so recurse. Only the httpx2 branch is
    message-suppressed (see `_http_failure_detail`); everything else here is an AssertionError
    authored in this file or a parse error over a hub RESPONSE body, so its message is safe.
    """
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(_failure_detail(sub, endpoint) for sub in exc.exceptions)
    if isinstance(exc, httpx2.HTTPError):
        return _http_failure_detail(exc, endpoint)
    return f"{type(exc).__name__}: {exc}"


def _load_hub_config() -> dict:
    """Reuse tests/e2e_test.py's config resolution and endpoint-path construction.

    Imported lazily because it needs this file's directory on sys.path first. Deriving the
    endpoint from HubitatMcpClient means the two legs can never disagree about the cloud-vs-LAN
    path shape (a genuine trap: the cloud form already carries /api/<UUID>/).

    The TOKEN has to be appended here. `HubitatMcpClient.endpoint` is the bare `<prefix>/mcp`
    and that client passes `access_token` per request; the SDK's transport takes one URL and
    nothing else, so a tokenless URL earns a bare 401. (Hubitat's OAuth layer also accepts the
    token as `Authorization: Bearer` on both the LAN endpoint and the cloud relay -- covered by
    tests/e2e_test.py's bearer scenario -- but the query form stays the canonical one here.)
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        import e2e_test
    except ImportError as exc:
        raise RuntimeError(
            f"Could not import tests/e2e_test.py ({exc}); it provides the shared hub config. "
            "Install its dependency with `pip install requests`."
        ) from exc
    config = e2e_test.load_config()
    client = e2e_test.HubitatMcpClient(
        hub_url=config["hub_url"], app_id=config["app_id"], access_token=config["access_token"]
    )
    # Direction matters: the tokenized URL is derived from the token-free one, never the reverse.
    safe_endpoint = client.endpoint
    assert "access_token" not in safe_endpoint, \
        "internal: the endpoint used for printed messages must never carry the token"
    separator = "&" if "?" in safe_endpoint else "?"
    endpoint = f"{safe_endpoint}{separator}access_token={config['access_token']}"
    return {
        # Passed to streamable_http_client and nothing else -- never printed, never sliced.
        "endpoint": endpoint,
        "safe_endpoint": safe_endpoint,
        "supported_versions": e2e_test.SUPPORTED_PROTOCOL_VERSIONS,
    }


def build_http_client(trace: RequestTrace) -> httpx2.AsyncClient:
    """Build the observer-only client accepted by the SDK's official HTTP transport."""
    return httpx2.AsyncClient(
        follow_redirects=True,
        timeout=httpx2.Timeout(REQUEST_TIMEOUT_SECONDS, read=SSE_READ_TIMEOUT_SECONDS),
        event_hooks={"request": [trace.record_request], "response": [trace.record_response]},
    )


class ModernScenarios:
    """Run every general conformance scenario through one modern high-level Client."""

    def __init__(self, config: dict, trace: RequestTrace) -> None:
        self.config = config
        self.trace = trace
        self.results: list[tuple[str, bool, str]] = []

    def _record(self, name: str, error: str | None) -> None:
        self.results.append((name, error is None, error or ""))
        print(f"  [{'PASS' if error is None else 'FAIL'}] {name}" + (f"\n         {error}" if error else ""))

    async def _run(self, name: str, coro_fn) -> bool:
        try:
            await coro_fn()
        except Exception as exc:
            # Any exception is a scenario failure, AssertionError included -- recorded so
            # the independent scenarios after it still run and report.
            self._record(name, _failure_detail(exc, self.config['safe_endpoint']))
            return False
        self._record(name, None)
        return True

    async def run_all(self, client: Client) -> bool:
        await self._run("high-level Client is pinned to MCP 2026-07-28",
                        lambda: self._modern_connection(client))
        await self._run("tools/list parses through the SDK's models and the catalog is sane",
                        lambda: self._list_tools(client))
        await self._run("a benign tools/call (hub_get_info) parses as a CallToolResult",
                        lambda: self._call_tool(client))
        await self._run("resources/list + templates parse through the SDK's modern models",
                        lambda: self._list_resources(client))
        await self._run("resources/read round-trips a guide section and the live context summary",
                        lambda: self._read_resources(client))
        return all(ok for _, ok, _ in self.results)

    async def _modern_connection(self, client: Client) -> None:
        assert client.protocol_version == MODERN_PROTOCOL_VERSION, (
            f"Client protocol is {client.protocol_version!r}, expected {MODERN_PROTOCOL_VERSION!r}"
        )
        assert client.input_required_max_rounds == DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS, (
            "the harness must retain the SDK's default input-required round limit; "
            f"saw {client.input_required_max_rounds}"
        )
        assert MODERN_PROTOCOL_VERSION == self.config["supported_versions"][0], (
            "the E2E client pin must equal the server's preferred advertised version"
        )
        print(f"         protocol={client.protocol_version} "
              f"input_required_max_rounds={client.input_required_max_rounds}")

    async def _list_tools(self, client: Client) -> None:
        # Every entry is parsed into types.Tool by the SDK (name required, inputSchema
        # required); the assertions here add catalog SANITY on top of that shape check.
        result = await client.list_tools(cache_mode="refresh")
        tools = result.tools
        assert len(tools) > 5, f"implausibly small catalog ({len(tools)} tools) -- did the masters hide everything?"
        names = [t.name for t in tools]
        duplicates = sorted({n for n in names if names.count(n) > 1})
        assert not duplicates, f"tools/list advertises duplicate names: {duplicates}"
        misprefixed = sorted(n for n in names if not n.startswith("hub_"))
        assert not misprefixed, f"every tool name must carry the hub_ service prefix: {misprefixed}"
        undescribed = sorted(t.name for t in tools if not (t.description or "").strip())
        assert not undescribed, f"tools advertised with no description: {undescribed}"
        badschema = sorted(t.name for t in tools if (t.input_schema or {}).get("type") != "object")
        assert not badschema, f"inputSchema root must be type 'object': {badschema}"
        assert "hub_get_info" in names, (
            "hub_get_info is a flat top-level tool (never behind a gateway) and the tools/call "
            f"scenario calls it; it is missing from a {len(tools)}-tool catalog"
        )
        print(f"         {len(tools)} tools advertised, all hub_-prefixed with object inputSchemas")

    async def _call_tool(self, client: Client) -> None:
        # Benign and read-only: hub_get_info mutates nothing, so this is safe against the
        # shared test hub. The SDK parses the reply as types.CallToolResult.
        result = await client.call_tool("hub_get_info", {})
        assert result.result_type == "complete", \
            f"hub_get_info returned non-terminal result_type={result.result_type!r}"
        assert result.is_error is not True, f"hub_get_info reported a tool error: {result.content!r}"
        assert result.content, "CallToolResult carried no content blocks"
        block = result.content[0]
        assert block.type == "text", f"expected a text content block, got {block.type!r}"
        payload = json.loads(block.text)
        assert isinstance(payload, dict), f"hub_get_info payload is not an object: {type(payload)}"
        # Never echo the payload: hub_get_info carries the hub's name, local IP, time zone,
        # latitude/longitude and zip code -- what the tool's own read-master gate calls
        # "personally identifiable data" -- and this suite runs in a PUBLIC repo whose Actions
        # logs are world-readable and permanent. Report the keys and the error field only.
        assert payload.get("success") is not False, \
            f"hub_get_info returned a failure envelope: keys={sorted(payload)}, error={payload.get('error')!r}"
        print(f"         hub_get_info returned {len(block.text)} chars of JSON, keys={sorted(payload)[:6]}")

    async def _list_resources(self, client: Client) -> None:
        # Every entry is parsed into types.Resource by the SDK (uri + name required). The
        # assertions add catalog sanity: the guide sections and both live context resources
        # are advertised, and the templates surface is empty rather than -32601.
        result = await client.list_resources(cache_mode="refresh")
        uris = [str(r.uri) for r in result.resources]
        assert len(uris) == len(set(uris)), "resources/list advertises duplicate URIs"
        guide_uris = [u for u in uris if u.startswith("hubitat://guide/")]
        assert guide_uris, f"no hubitat://guide/* resources advertised (saw: {uris[:5]})"
        for expected in ("hubitat://context-summary", "hubitat://context"):
            assert expected in uris, f"{expected} missing from resources/list: {uris[:8]}"
        unnamed = [str(r.uri) for r in result.resources if not r.name]
        assert not unnamed, f"resources advertised without the required name: {unnamed}"
        templates = await client.list_resource_templates(cache_mode="refresh")
        assert templates.resource_templates == [], \
            f"expected an empty template list, got: {templates.resource_templates!r}"
        print(f"         {len(uris)} resources advertised ({len(guide_uris)} guide sections + context pair)")

    async def _read_resources(self, client: Client) -> None:
        # Round-trips the URI OBJECT the SDK parsed off resources/list back into
        # resources/read -- if pydantic's AnyUrl normalization ever disagrees with the
        # server's literal URI strings, this is the scenario that catches it. Both reads
        # are read-only against the shared hub.
        listed = await client.list_resources(cache_mode="refresh")
        by_uri = {str(r.uri): r for r in listed.resources}
        guide = next(r for r in listed.resources if str(r.uri).startswith("hubitat://guide/"))
        guide_result = await client.read_resource(str(guide.uri), cache_mode="refresh")
        content = guide_result.contents[0]
        assert str(content.uri) == str(guide.uri), \
            f"read echoed a different uri: sent {guide.uri!r}, got {content.uri!r}"
        guide_text = getattr(content, "text", None)
        assert guide_text, f"guide read returned no text content: {content!r}"
        assert content.mime_type == "text/markdown", f"unexpected guide mimeType: {content.mime_type!r}"

        summary = by_uri["hubitat://context-summary"]
        summary_result = await client.read_resource(str(summary.uri), cache_mode="refresh")
        text = getattr(summary_result.contents[0], "text", "") or ""
        assert text.startswith("Mode:") or "No devices" in text, (
            "context-summary must lead with the mode header (or the explicit no-devices "
            f"message on an empty install); got: {text[:80]!r}"
        )
        print(f"         read {str(guide.uri)!r} ({len(guide_text)} chars) and the "
              f"context summary ({len(text)} chars)")

    async def run_modern_header_contract(self) -> bool:
        name = "every SDK POST carried complete 2026-07-28 routing headers and was served"
        return await self._run(name, self._modern_header_contract)

    async def _modern_header_contract(self) -> None:
        summary = summarize_modern_posts(self.trace.posts())
        print(f"         {summary['posts']} modern SDK POST(s) carried mirrored routing "
              f"headers, all answered {summary['statuses']}")


def _tool_payload(result: CallToolResult, operation: str) -> dict[str, Any]:
    """Return a successful terminal JSON-object tool payload from the v2 model."""
    assert isinstance(result, CallToolResult), (
        f"{operation} returned {type(result).__name__}, not CallToolResult"
    )
    assert result.result_type == "complete", (
        f"{operation} returned non-terminal result_type={result.result_type!r}"
    )
    assert result.is_error is False, f"{operation} returned is_error=true"
    text_blocks = [block.text for block in result.content
                   if block.type == "text" and getattr(block, "text", None)]
    assert text_blocks, f"{operation} returned no text content"
    payload = json.loads(text_blocks[0])
    assert isinstance(payload, dict), f"{operation} payload is {type(payload).__name__}, not object"
    assert payload.get("success") is not False, (
        f"{operation} returned success=false: error={payload.get('error')!r}"
    )
    return payload


class ModernMrtrScenario:
    """Prove automatic state-only continuation with one high-level SDK call."""

    GATEWAY = "hub_manage_rule_machine"

    def __init__(self, config: dict, trace: RequestTrace) -> None:
        self.config = config
        self.trace = trace
        self.result: tuple[str, bool, str] | None = None

    async def run(self, client: Client) -> bool:
        name = "one high-level Client.call_tool completes a >10s modern MRTR write"
        try:
            await self._run(client)
        except Exception as exc:
            error = _failure_detail(exc, self.config["safe_endpoint"])
            self.result = (name, False, error)
            print(f"  [FAIL] {name}\n         {error}")
            return False
        self.result = (name, True, "")
        print(f"  [PASS] {name}")
        return True

    async def _run(self, client: Client) -> None:
        assert client.protocol_version == MODERN_PROTOCOL_VERSION, (
            f"pinned client uses {client.protocol_version!r}, expected {MODERN_PROTOCOL_VERSION!r}"
        )
        assert client.input_required_max_rounds == DEFAULT_SDK_INPUT_REQUIRED_MAX_ROUNDS, (
            "the proof must use the SDK's default input-required round limit; "
            f"saw {client.input_required_max_rounds}"
        )
        tools = await client.list_tools(cache_mode="refresh")
        assert self.GATEWAY in {tool.name for tool in tools.tools}, (
            f"{self.GATEWAY} is absent from the modern tools/list catalog"
        )

        fixture_name = f"BAT_E2E_SDK_MRTR_{uuid.uuid4().hex[:12]}"
        fixture_id: str | None = None
        guide_result = await client.call_tool(
            "hub_get_tool_guide", {"section": "best_practice_reference"},
        )
        guide_payload = _tool_payload(guide_result, "best-practice guide read")
        bps_key = extract_bps_acknowledgment_key(guide_payload.get("content"))
        primary_error: BaseException | None = None
        try:
            created = await client.call_tool(self.GATEWAY, {
                "tool": "hub_set_rule",
                "args": {
                    "name": fixture_name,
                    "confirm": True,
                    "bestPracticeKey": bps_key,
                },
            })
            create_payload = _tool_payload(created, "fixture create")
            fixture_id = str(create_payload.get("appId") or "") or None
            assert fixture_id, "fixture create returned no appId"

            requested_actions = [
                {"capability": "log", "message": f"SDK MRTR proof {index}"}
                for index in range(1, 7)
            ]
            trace_mark = self.trace.mark()
            started = time.monotonic()
            result = await client.call_tool(self.GATEWAY, {
                "tool": "hub_set_rule",
                "args": {
                    "appId": fixture_id,
                    "addActions": requested_actions,
                    "confirm": True,
                    "bestPracticeKey": bps_key,
                },
            })
            logical_elapsed = time.monotonic() - started
            payload = _tool_payload(result, "slow fixture edit")
            assert not payload.get("partial"), f"slow fixture edit was partial: {payload}"
            action_results = payload.get("actions") or []
            assert len(action_results) == len(requested_actions), (
                "slow fixture edit did not return every requested mutation result: "
                f"requested={len(requested_actions)}, returned={len(action_results)}"
            )
            assert all(isinstance(action, dict) and action.get("success") is not False
                       for action in action_results), (
                f"slow fixture edit contained a failed mutation: {action_results}"
            )
            legs = self.trace.tool_call_legs(trace_mark, self.GATEWAY)
            summary = summarize_mrtr_proof(legs, logical_elapsed)
            server_rounds = (payload.get("mrtr") or {}).get("rounds")
            coordination_rounds = assert_mrtr_owner_rounds(
                server_rounds, summary["continuation_rounds"])
            durations = ", ".join(f"{leg['duration']:.3f}s" for leg in legs)
            print(
                "         modern MRTR: "
                f"legs={summary['legs']} continuation_rounds={summary['continuation_rounds']} "
                f"owner_rounds={server_rounds} coordination_rounds={coordination_rounds} "
                f"sdk_round_limit={summary['sdk_round_limit']} "
                f"logical={summary['logical_elapsed']:.3f}s max_leg={summary['max_leg_elapsed']:.3f}s "
                f"leg_durations=[{durations}]"
            )
            # Read the persisted app state through a second ordinary high-level SDK call,
            # outside the timing mark/window above. Successful write-shaped rows alone do
            # not prove that Rule Machine stored the intended distinct values.
            readback = await client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config",
                "args": {"appId": fixture_id, "includeSettings": True},
            })
            readback_payload = _tool_payload(readback, "slow fixture persisted-state readback")
            assert_exact_rule_log_messages(
                readback_payload,
                [action["message"] for action in requested_actions],
                operation="SDK MRTR proof readback",
            )
        except BaseException as exc:
            primary_error = exc
            raise
        finally:
            cleanup_error = await cleanup_preserving_primary(
                lambda: self._cleanup_fixture(
                    client, fixture_name, fixture_id, bps_key,
                ),
                primary_error,
            )
            if cleanup_error is not None:
                print(
                    "         [WARN] fixture cleanup also failed: "
                    f"{_failure_detail(cleanup_error, self.config['safe_endpoint'])}"
                )

    async def _cleanup_fixture(
        self,
        client: Client,
        fixture_name: str,
        fixture_id: str | None,
        bps_key: str,
    ) -> None:
        """Delete only this run's exact UUID-named app; never sweep backups or probe apps."""
        target_id = fixture_id
        if target_id is None:
            async def _list_rules() -> list[dict[str, Any]]:
                listed = await client.call_tool(self.GATEWAY, {
                    "tool": "hub_list_rules", "args": {},
                })
                rules = _tool_payload(listed, "fixture cleanup lookup").get("rules") or []
                assert isinstance(rules, list), "fixture cleanup lookup returned non-list rules"
                return rules

            target_id = await find_exact_fixture_id_with_settle(
                _list_rules,
                fixture_name,
                sleep=anyio.sleep,
            )
        if target_id is None:
            return
        deleted = await client.call_tool(self.GATEWAY, {
            "tool": "hub_delete_native_app",
            "args": {
                "appId": target_id,
                "confirm": True,
                "bestPracticeKey": bps_key,
            },
        })
        _tool_payload(deleted, "fixture cleanup delete")


async def _main_async(config: dict) -> int:
    trace = RequestTrace()
    # safe_endpoint, never config['endpoint'] -- the live URL carries the access token.
    print(f"Connecting the official MCP Python SDK client to {config['safe_endpoint']} ...")
    # The caller-owned httpx2 client is observer-only. Client receives the SDK's official
    # transport object and owns every request-to-request continuation. Pinning the mode to
    # the version string prevents Client's auto mode from falling back to legacy.
    async with build_http_client(trace) as http_client:
        modern_transport = streamable_http_client(config["endpoint"], http_client=http_client)
        async with Client(
            modern_transport, mode=MODERN_PROTOCOL_VERSION, cache=None,
        ) as modern_client:
            scenarios = ModernScenarios(config, trace)
            scenarios_ok = await scenarios.run_all(modern_client)
            mrtr = ModernMrtrScenario(config, trace)
            mrtr_ok = await mrtr.run(modern_client)
            headers_ok = await scenarios.run_modern_header_contract()

    results = scenarios.results + ([mrtr.result] if mrtr.result else [])
    passed = sum(1 for _, good, _ in results if good)
    total = len(results)
    print(f"\n{'=' * 60}\nSDK conformance: {passed}/{total} scenarios passed "
          f"(mcp=={pinned_sdk_version()})\n{'=' * 60}")
    ok = scenarios_ok and mrtr_ok and headers_ok
    if not ok:
        for name, good, err in results:
            if not good:
                print(f"  FAILED: {name}\n          {err}")
    return 0 if ok else 1


def main() -> None:
    pin = pinned_sdk_version()
    import importlib.metadata as metadata
    installed = metadata.version("mcp")
    if installed != pin:
        # Not a skip and not a warning: a verdict from an unpinned SDK is not the verdict
        # CI produces, so reporting one would be misleading.
        print(f"ERROR: the installed MCP SDK is {installed}, but {REQUIREMENTS.name} pins {pin}.")
        print(f"  Install the pin (or update the file deliberately -- see its header):\n    {_INSTALL_HINT}")
        sys.exit(1)

    stale = deprecated_sdk_usages()
    if stale:
        print(f"ERROR: this harness calls {len(stale)} SDK entry point(s) that mcp {pin} marks deprecated:")
        for line in stale:
            print(f"    {line}")
        print("  Migrate to the named replacement -- a conformance harness must not itself ride a "
              "deprecated API (see docs/testing.md § Conformance harness).")
        sys.exit(1)

    try:
        config = _load_hub_config()
    except RuntimeError as exc:
        print(f"ERROR: {exc}")
        sys.exit(1)

    # The SDK logs the endpoint URL -- token and all -- at DEBUG ("Connecting to
    # StreamableHTTP endpoint: {url}"). Nothing configures logging here so it is not emitted
    # today, but this script must not be one global logging.basicConfig(DEBUG) away from
    # printing a live credential, and nothing in this file can reach a library's own log call.
    logging.getLogger("mcp.client.streamable_http").setLevel(logging.INFO)

    print("=" * 60)
    print(f"Hubitat MCP Server — official-SDK conformance (mcp=={pin})")
    print("=" * 60)
    try:
        code = anyio.run(_main_async, config)
    except Exception as exc:
        # Report the transport failure as one actionable line rather than dumping an anyio
        # exception-group traceback -- an unreachable hub is the common case here.
        print(f"\nERROR: the SDK client could not complete a session against the hub: "
              f"{_failure_detail(exc, config['safe_endpoint'])}")
        print("  Check that the hub is online, the MCP endpoint URL and access token are correct, "
              "and that the endpoint answers Streamable HTTP POSTs.")
        sys.exit(1)
    sys.exit(code)


if __name__ == "__main__":
    main()
