#!/usr/bin/env python3
"""
Hubitat MCP Server — conformance scenarios driven by the OFFICIAL MCP Python SDK.

The hub sandbox whitelists imports and cannot load jars, so this server's protocol layer is
hand-written. That makes an independent referee necessary: here the official `mcp` package's
client speaks Streamable HTTP to a real hub endpoint and exercises the real negotiation path,
with every response parsed through the SDK's own validators rather than through assertions
written against this repo's reading of the spec. The companion leg is
`src/test/groovy/server/McpWireSchemaConformanceSpec.groovy`. Read docs/testing.md
§ Conformance harness before changing either.

NO SILENT SKIPS. A missing `mcp` package, a version other than the pin, or missing hub config
all FAIL with a remediation message. A skip here would look like coverage.

The legacy scenarios preserve handshake-era compatibility. A separate modern scenario creates
one uniquely named BAT_E2E_ Rule Machine fixture, proves one high-level SDK call continues across
several cloud-relay requests, and removes only that exact fixture in a `finally`. The other writing
scenario flips the advanced `publishOutputSchemas` setting ON and restores it in a `finally`.

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
    """Any SDK entry point this file calls that the pinned SDK marks `@deprecated`.

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
    return [f"{label}: {fn.__deprecated__}"
            for label, fn in checked if getattr(fn, "__deprecated__", None)]


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
        "initialize_versions": e2e_test.INITIALIZE_PROTOCOL_VERSIONS,
        "supported_versions": e2e_test.SUPPORTED_PROTOCOL_VERSIONS,
    }


def build_http_client(trace: RequestTrace) -> httpx2.AsyncClient:
    """Build the observer-only client accepted by the SDK's official HTTP transport."""
    return httpx2.AsyncClient(
        follow_redirects=True,
        timeout=httpx2.Timeout(REQUEST_TIMEOUT_SECONDS, read=SSE_READ_TIMEOUT_SECONDS),
        event_hooks={"request": [trace.record_request], "response": [trace.record_response]},
    )


class LegacyScenarios:
    """Runs handshake-era conformance through the SDK's high-level Client."""

    def __init__(self, config: dict, trace: RequestTrace) -> None:
        self.config = config
        self.trace = trace
        self.results: list[tuple[str, bool, str]] = []
        self.negotiated: str | None = None

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
        await self._run("high-level Client negotiates the expected legacy revision",
                        lambda: self._initialize(client))
        await self._run("tools/list parses through the SDK's models and the catalog is sane",
                        lambda: self._list_tools(client))
        await self._run("a benign tools/call (hub_get_info) parses as a CallToolResult",
                        lambda: self._call_tool(client))
        await self._run("resources/list + templates parse through the SDK's models and match the "
                        "advertised capability",
                        lambda: self._list_resources(client))
        await self._run("resources/read round-trips a guide section and the live context summary",
                        lambda: self._read_resources(client))
        await self._run("with publishOutputSchemas ON, the SDK's own validator accepts the "
                        "structuredContent against the advertised outputSchema",
                        lambda: self._published_output_schema(client))
        await self._run("every post-initialize POST carried the legacy MCP-Protocol-Version header "
                        "and was served",
                        self._legacy_header_contract)
        return all(ok for _, ok, _ in self.results)

    async def _initialize(self, client: Client) -> None:
        # Entering Client(mode="legacy") already performed initialize through the SDK. These
        # high-level properties expose the validated result without using the low-level session API.
        self.negotiated = client.protocol_version
        expected = self.config["initialize_versions"][0]
        assert self.negotiated == expected, (
            f"initialize echoed {self.negotiated!r}; expected the newest revision the handshake may "
            f"negotiate, {expected!r}. initialize is legacy-capped on purpose -- 2026-07-28 deleted "
            f"the handshake, so it must never hand back the modern revision."
        )
        # Cross-era invariant: whatever initialize negotiates, the SDK will send back as an
        # MCP-Protocol-Version header on every later POST -- so a version outside the
        # server's TRANSPORT list would earn a -32022 on the very next request.
        assert self.negotiated in self.config["supported_versions"], (
            f"initialize negotiated {self.negotiated!r}, which is not in the transport's supported "
            f"list {self.config['supported_versions']} -- the next request's header would be rejected."
        )
        info = client.server_info
        capabilities = client.server_capabilities
        assert info is not None and info.name == "hubitat-mcp-rule-server", \
            f"unexpected serverInfo: {info!r}"
        assert info.version, "serverInfo carries no version"
        assert capabilities.tools is not None, \
            f"server must advertise the tools capability: {capabilities!r}"
        # Issue #366: resources advertised, with both change-notification flags false --
        # this endpoint is request-response only (no SSE), so a true here would promise
        # notifications the transport cannot deliver.
        assert capabilities.resources is not None, \
            f"server must advertise the resources capability: {capabilities!r}"
        assert capabilities.resources.subscribe is not True, \
            "resources.subscribe must not be advertised true on a no-SSE endpoint"
        assert capabilities.resources.list_changed is not True, \
            "resources.listChanged must not be advertised true on a no-SSE endpoint"
        assert client.instructions and client.instructions.strip(), \
            "initialize must carry non-empty instructions"
        print(f"         negotiated={self.negotiated} server={info.name} v{info.version}")

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
        badschema = sorted(t.name for t in tools if (t.inputSchema or {}).get("type") != "object")
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
        # Issue #366: every entry is parsed into types.Resource by the SDK (uri + name
        # required). The assertions add catalog sanity: the guide sections and both live
        # context resources are advertised, and the templates surface is empty rather
        # than -32601.
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

    async def _published_output_schema(self, client: Client) -> None:
        """Hand the SDK's OWN structuredContent validator something to referee (issue #342).

        Client.call_tool validates `structuredContent` against any advertised
        outputSchema and raises "has an output schema but did not return structured content" /
        "Invalid structured content returned by tool" -- but `publishOutputSchemas` is OFF by
        default, so every cached schema is None and that branch never executes. Flipping it ON
        is what makes the SDK judge the emitted WIRE form (`_wireOutputSchema`, `required`
        arrays stripped) against a real result; nothing else validates that pairing with a real
        validator.

        The flip is inside the try for the same reason
        tests/e2e_test.py's sibling does it: hub_update_mcp_settings is replay-safe, but its
        RESPONSE can still be lost after the mutation committed hub-side, and a raise outside
        the try would leak the toggle ON for the rest of the run.
        """
        async def _set(enabled: bool):
            return await client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {"settings": {"publishOutputSchemas": enabled}, "confirm": True}})

        def _advertised(result) -> list[str]:
            return sorted(t.name for t in result.tools if t.output_schema)

        # The prior value is read off the catalog rather than assumed: restore has to put back
        # what was there, and this is the same surface the restore is verified against.
        was_on = bool(_advertised(await client.list_tools(cache_mode="refresh")))
        try:
            flip = await _set(True)
            assert flip.is_error is not True, (
                "could not turn publishOutputSchemas ON, so the SDK's validator has nothing to "
                f"referee: {flip.content!r}. hub_update_mcp_settings is developer-mode gated -- "
                "check enableDeveloperMode on this hub."
            )
            # A fresh list_tools is REQUIRED, not cosmetic: call_tool only refreshes the cache
            # for a name it has never seen, and the tools/list scenario above already cached
            # hub_get_info's schema as None.
            advertised = _advertised(await client.list_tools(cache_mode="refresh"))
            assert "hub_get_info" in advertised, (
                "publishOutputSchemas reads ON but hub_get_info advertises no outputSchema, so the "
                f"SDK's validator has nothing to judge (advertised: {advertised[:5]}). The FLAT "
                "catalog always strips outputSchema -- if this hub runs useGateways=false, that is why."
            )
            # The referee: the SDK validates structuredContent against that schema with
            # jsonschema and raises if it does not conform. An assert here would be OUR check;
            # the value is that the raise comes from the SDK.
            result = await client.call_tool("hub_get_info", {})
            assert result.is_error is not True, f"hub_get_info reported a tool error: {result.content!r}"
            # Reached only if the SDK skipped validation entirely (it raises on a schema-advertising
            # tool whose result has no structuredContent), which is the silent-no-op this scenario
            # exists to catch -- the whole point is that this branch used to be dead.
            assert result.structured_content, \
                "no structuredContent and no SDK raise -- the validation path did not run"
            print(f"         SDK validated structuredContent against hub_get_info's advertised "
                  f"outputSchema ({len(advertised)} tools advertising)")
        finally:
            restored = False
            last: Exception | None = None
            for _ in range(3):
                try:
                    await _set(was_on)
                    if bool(_advertised(await client.list_tools(cache_mode="refresh"))) == was_on:
                        restored = True
                        break
                except Exception as exc:
                    last = exc
                await anyio.sleep(1.0)
            assert restored, (
                f"CRITICAL: could not restore publishOutputSchemas={was_on} on the hub; every "
                f"later client sees the wrong catalog until it is fixed by hand. Last error: "
                f"{_failure_detail(last, self.config['safe_endpoint']) if last else 'none'}"
            )

    async def _legacy_header_contract(self) -> None:
        # THE compatibility pin. Reading MCP-Protocol-Version's PRESENCE as "modern era"
        # would 400 + -32020 every request below -- i.e. every deployed client, all of which
        # are legacy-era. The era switch has to be the header's VALUE.
        posts = self.trace.posts_after_initialize()
        assert posts, (
            "no POST carried an MCP-Protocol-Version header, so this scenario proved nothing. "
            "Either the SDK stopped stamping the negotiated version or the trace hook is not wired."
        )
        wrong = [p["mcp_protocol_version"] for p in posts
                 if p["mcp_protocol_version"] != self.negotiated]
        assert not wrong, f"expected every header to be the negotiated {self.negotiated!r}, saw {sorted(set(wrong))}"
        modern_only = [p for p in posts if p["mcp_method"] is not None or p["mcp_name"] is not None]
        assert not modern_only, (
            "the SDK sent Mcp-Method/Mcp-Name, which exist only in 2026-07-28 -- this scenario's "
            f"premise no longer holds and the SDK pin's era must be re-read: {modern_only}"
        )
        # "Served" is the half that matters and it has to be READ, not inferred: an era-detection
        # regression 400s every one of these, and a scenario that only inspects the REQUEST hook
        # would still pass and print "all served" while every other scenario failed.
        answered = [p for p in posts if p["status"] is not None]
        assert len(answered) == len(posts), (
            f"{len(posts)} legacy-versioned POST(s) went out but only {len(answered)} came back -- "
            "the response hook is not wired, so nothing here proves the server served them."
        )
        rejected = sorted({r["status"] for r in answered} - {HTTPStatus.OK, HTTPStatus.ACCEPTED})
        assert not rejected, (
            f"the server did not serve every legacy-versioned POST: saw status {rejected}. A 400 "
            "here is the presence-vs-value era bug -- it breaks every deployed client, all of "
            "which send MCP-Protocol-Version and no Mcp-Method/Mcp-Name."
        )
        print(f"         {len(posts)} legacy-versioned POST(s) sent with no Mcp-Method/Mcp-Name, "
              f"all answered {sorted({r['status'] for r in answered})}")


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
            f"auto mode negotiated {client.protocol_version!r}, expected {MODERN_PROTOCOL_VERSION!r}"
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
        try:
            created = await client.call_tool(self.GATEWAY, {
                "tool": "hub_set_rule",
                "args": {"name": fixture_name, "confirm": True},
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
            assert server_rounds == summary["continuation_rounds"], (
                "HTTP continuation count does not match owner execution slices; "
                f"http={summary['continuation_rounds']} server={server_rounds}. "
                "A contention wait must not be mistaken for an ordinary ~8-second owner slice."
            )
            durations = ", ".join(f"{leg['duration']:.3f}s" for leg in legs)
            print(
                "         modern MRTR: "
                f"legs={summary['legs']} continuation_rounds={summary['continuation_rounds']} "
                f"sdk_round_limit={summary['sdk_round_limit']} "
                f"logical={summary['logical_elapsed']:.3f}s max_leg={summary['max_leg_elapsed']:.3f}s "
                f"leg_durations=[{durations}]"
            )
        finally:
            await self._cleanup_fixture(client, fixture_name, fixture_id)

    async def _cleanup_fixture(
        self, client: Client, fixture_name: str, fixture_id: str | None,
    ) -> None:
        """Delete only this run's exact UUID-named app; never sweep backups or probe apps."""
        target_id = fixture_id
        if target_id is None:
            listed = await client.call_tool(self.GATEWAY, {
                "tool": "hub_list_rules", "args": {},
            })
            rules = _tool_payload(listed, "fixture cleanup lookup").get("rules") or []
            matches = [rule for rule in rules
                       if (rule.get("name") or rule.get("label")) == fixture_name]
            assert len(matches) <= 1, (
                f"refusing ambiguous cleanup: {len(matches)} rules exactly match {fixture_name!r}"
            )
            if matches:
                target_id = str(matches[0].get("id") or matches[0].get("appId") or "") or None
        if target_id is None:
            return
        deleted = await client.call_tool(self.GATEWAY, {
            "tool": "hub_delete_native_app",
            "args": {"appId": target_id, "confirm": True},
        })
        _tool_payload(deleted, "fixture cleanup delete")


async def _main_async(config: dict) -> int:
    trace = RequestTrace()
    # safe_endpoint, never config['endpoint'] -- the live URL carries the access token.
    print(f"Connecting the official MCP Python SDK client to {config['safe_endpoint']} ...")
    # The caller-owned httpx2 client is observer-only. Client receives the SDK's official
    # transport object and owns negotiation plus every request-to-request continuation.
    async with build_http_client(trace) as http_client:
        legacy_transport = streamable_http_client(config["endpoint"], http_client=http_client)
        async with Client(legacy_transport, mode="legacy", cache=None) as legacy_client:
            legacy = LegacyScenarios(config, trace)
            legacy_ok = await legacy.run_all(legacy_client)

        modern_transport = streamable_http_client(config["endpoint"], http_client=http_client)
        async with Client(modern_transport, mode="auto", cache=None) as modern_client:
            modern = ModernMrtrScenario(config, trace)
            modern_ok = await modern.run(modern_client)

    results = legacy.results + ([modern.result] if modern.result else [])
    passed = sum(1 for _, good, _ in results if good)
    total = len(results)
    print(f"\n{'=' * 60}\nSDK conformance: {passed}/{total} scenarios passed "
          f"(mcp=={pinned_sdk_version()})\n{'=' * 60}")
    ok = legacy_ok and modern_ok
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
