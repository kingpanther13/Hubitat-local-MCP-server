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

Every scenario is read-only EXCEPT the outputSchema one, which flips the advanced
`publishOutputSchemas` setting ON and restores it in a `finally` — that toggle is the only way
to hand the SDK's own structuredContent validator a schema to referee.

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
from http import HTTPStatus
from pathlib import Path
from typing import Any

_INSTALL_HINT = "pip install -r tests/sdk-conformance-requirements.txt"

try:
    import anyio
    import httpx
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client
except ImportError as exc:
    # A FAILURE, never a skip: these scenarios are the only thing proving this server
    # interoperates with the reference client, so "not installed" must never read green.
    print(f"ERROR: the official MCP Python SDK is not importable ({exc}).")
    print(f"  This is a FAILURE, not a skip -- install the pinned SDK and re-run:\n    {_INSTALL_HINT}")
    sys.exit(1)

REQUIREMENTS = Path(__file__).resolve().parent / "sdk-conformance-requirements.txt"

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
        (f"ClientSession.{name}", getattr(ClientSession, name))
        for name in ("initialize", "send_ping", "list_tools", "call_tool")
    ]
    return [f"{label}: {fn.__deprecated__}"
            for label, fn in checked if getattr(fn, "__deprecated__", None)]


def _http_failure_detail(exc: httpx.HTTPError, endpoint: str) -> str:
    """Describe an httpx failure from STRUCTURED FIELDS ONLY -- never from `str(exc)`.

    httpx quotes the full request URL in every URL-bearing exception message and this endpoint
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
    actionable -- the real cause is a sub-exception, so recurse. Only the httpx branch is
    message-suppressed (see `_http_failure_detail`); everything else here is an AssertionError
    authored in this file or a parse error over a hub RESPONSE body, so its message is safe.
    """
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(_failure_detail(sub, endpoint) for sub in exc.exceptions)
    if isinstance(exc, httpx.HTTPError):
        return _http_failure_detail(exc, endpoint)
    return f"{type(exc).__name__}: {exc}"


def _load_hub_config() -> dict:
    """Reuse tests/e2e_test.py's config resolution and endpoint-path construction.

    Imported lazily because it needs this file's directory on sys.path first. Deriving the
    endpoint from HubitatMcpClient means the two legs can never disagree about the cloud-vs-LAN
    path shape (a genuine trap: the cloud form already carries /api/<UUID>/).

    The TOKEN has to be appended here. `HubitatMcpClient.endpoint` is the bare `<prefix>/mcp`
    and that client passes `access_token` per request; the SDK's transport takes one URL and
    nothing else, so a tokenless URL earns a bare 401. There is no bearer fallback -- Hubitat's
    OAuth endpoints ignore Authorization and this server never reads it.
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


class RequestTrace:
    """Records the method and MCP-relevant headers of every outbound request, and the status
    every one came back with.

    Both observed through httpx `event_hooks` on the AsyncClient this file owns and hands to
    `streamable_http_client`. The request hook receives the fully-built `httpx.Request` AFTER
    httpx merged the client's headers with the SDK transport's per-request ones, so what lands
    here is what the SDK actually put on the wire, not a reconstruction of it.

    Deliberately records NO url: the endpoint carries the access token in its query, and these
    dicts are echoed verbatim in assertion messages.
    """

    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.responses: list[dict[str, Any]] = []

    @staticmethod
    def _mcp_fields(request: httpx.Request) -> dict[str, Any]:
        return {
            "method": request.method,
            "mcp-protocol-version": request.headers.get("mcp-protocol-version"),
            "mcp-method": request.headers.get("mcp-method"),
            "mcp-name": request.headers.get("mcp-name"),
            "mcp-session-id": request.headers.get("mcp-session-id"),
            "accept": request.headers.get("accept"),
        }

    async def record(self, request: httpx.Request) -> None:
        self.requests.append(self._mcp_fields(request))

    async def record_response(self, response: httpx.Response) -> None:
        # Status only, and never the body: a response hook fires before the body is read, so
        # touching `.text` here would consume a stream the SDK still needs.
        self.responses.append(dict(self._mcp_fields(response.request),
                                   status=int(response.status_code)))

    def build_client(self) -> httpx.AsyncClient:
        """The AsyncClient to hand `streamable_http_client` as `http_client=`.

        These values reproduce what the SDK's own (now-deprecated) wrapper built. The read
        timeout is inert against this server -- it answers `application/json` and issues no
        session id, so the SDK opens no GET SSE stream and sends no DELETE on close -- but it is
        kept at the SDK's default rather than silently tightened.

        NOTE: `streamable_http_client` does NOT manage a caller-supplied client's lifecycle, so
        the caller must use this inside `async with`.
        """
        return httpx.AsyncClient(
            follow_redirects=True,
            timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS, read=SSE_READ_TIMEOUT_SECONDS),
            event_hooks={"request": [self.record], "response": [self.record_response]},
        )

    def posts_after_initialize(self) -> list[dict[str, Any]]:
        """Every POST that carried a protocol-version header — i.e. everything the SDK sent
        after initialize told it which revision was negotiated."""
        return [r for r in self.requests
                if r["method"] == "POST" and r["mcp-protocol-version"] is not None]

    def answered_posts_after_initialize(self) -> list[dict[str, Any]]:
        """The same POSTs, seen from the RESPONSE side, so a scenario can assert the server
        actually served them instead of inferring it."""
        return [r for r in self.responses
                if r["method"] == "POST" and r["mcp-protocol-version"] is not None]


class Scenarios:
    """Runs the conformance scenarios over one SDK session, collecting per-scenario results."""

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

    async def run_all(self, session: ClientSession) -> bool:
        # initialize gates everything else: without a negotiated session the remaining
        # scenarios cannot run, so a failure here aborts rather than cascading.
        if not await self._run("initialize negotiates a legacy revision the SDK accepts",
                               lambda: self._initialize(session)):
            print("\n  initialize failed -- the remaining scenarios cannot run against an "
                  "unnegotiated session.")
            return False
        await self._run("ping returns a strict-parseable EmptyResult (no resultType on the legacy era)",
                        lambda: self._ping(session))
        await self._run("tools/list parses through the SDK's models and the catalog is sane",
                        lambda: self._list_tools(session))
        await self._run("a benign tools/call (hub_get_info) parses as a CallToolResult",
                        lambda: self._call_tool(session))
        await self._run("with publishOutputSchemas ON, the SDK's own validator accepts the "
                        "structuredContent against the advertised outputSchema",
                        lambda: self._published_output_schema(session))
        await self._run("every post-initialize POST carried the legacy MCP-Protocol-Version header "
                        "and was served",
                        self._legacy_header_contract)
        return all(ok for _, ok, _ in self.results)

    async def _initialize(self, session: ClientSession) -> None:
        # ClientSession.initialize() requests types.LATEST_PROTOCOL_VERSION and RAISES
        # RuntimeError if the echoed version is outside the SDK's SUPPORTED_PROTOCOL_VERSIONS
        # -- so simply getting a result back is the SDK refereeing our negotiation. It then sends
        # notifications/initialized, a notification-only POST the transport requires be answered
        # 202; the SDK special-cases that status but does not assert it, so the status check is
        # the legacy-header scenario's (via the response hook), not the SDK's.
        result = await session.initialize()
        self.negotiated = str(result.protocolVersion)
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
        assert result.serverInfo.name == "hubitat-mcp-rule-server", \
            f"unexpected serverInfo.name: {result.serverInfo.name!r}"
        assert result.serverInfo.version, "serverInfo carries no version"
        assert result.capabilities.tools is not None, \
            f"server must advertise the tools capability: {result.capabilities!r}"
        assert result.instructions and result.instructions.strip(), \
            "initialize must carry non-empty instructions"
        print(f"         negotiated={self.negotiated} server={result.serverInfo.name} "
              f"v{result.serverInfo.version}")

    async def _ping(self, session: ClientSession) -> None:
        # The #365 regression, through a real client. The Python SDK's EmptyResult is
        # extra="allow", so it would NOT reject a stray key -- the assertion below is OURS.
        # The strict referee (the TypeScript SDK's ResultSchema.strict()) is reproduced on
        # the unit side; what this proves is that a real client's keepalive round-trips and
        # that the legacy reply carries nothing beyond the modelled envelope.
        result = await session.send_ping()
        # This scenario's premise: resultType is not a DECLARED field on the SDK's Result model,
        # so a stray one lands in model_extra. A pin bump that models 2026-07-28 would move it
        # into model_fields and both assertions below would silently pass on every payload.
        assert "resultType" not in type(result).model_fields, (
            f"the pinned SDK now models resultType on {type(result).__name__}, so model_extra can "
            "no longer see it -- this scenario is blind as written and must be rewritten against "
            "the declared field (and the SDK's era re-read: 2026-07-28 support changes what this "
            "whole leg proves)."
        )
        extra = result.model_extra or {}
        assert "resultType" not in extra, (
            "the legacy ping reply carries resultType, which a strict EmptyResult parse "
            f"(the TypeScript SDK's ResultSchema.strict()) REJECTS: extra keys = {sorted(extra)}"
        )
        assert not extra, f"legacy ping must be exactly the modelled envelope; extra keys = {sorted(extra)}"

    async def _list_tools(self, session: ClientSession) -> None:
        # Every entry is parsed into types.Tool by the SDK (name required, inputSchema
        # required); the assertions here add catalog SANITY on top of that shape check.
        result = await session.list_tools()
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

    async def _call_tool(self, session: ClientSession) -> None:
        # Benign and read-only: hub_get_info mutates nothing, so this is safe against the
        # shared test hub. The SDK parses the reply as types.CallToolResult.
        result = await session.call_tool("hub_get_info", {})
        assert result.isError is not True, f"hub_get_info reported a tool error: {result.content!r}"
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

    async def _published_output_schema(self, session: ClientSession) -> None:
        """Hand the SDK's OWN structuredContent validator something to referee (issue #342).

        ClientSession.call_tool validates `structuredContent` against any advertised
        outputSchema and raises "has an output schema but did not return structured content" /
        "Invalid structured content returned by tool" -- but `publishOutputSchemas` is OFF by
        default, so every cached schema is None and that branch never executes. Flipping it ON
        is what makes the SDK judge the emitted WIRE form (`_wireOutputSchema`, `required`
        arrays stripped) against a real result; nothing else validates that pairing with a real
        validator.

        THE ONLY WRITING SCENARIO. The flip is inside the try for the same reason
        tests/e2e_test.py's sibling does it: hub_update_mcp_settings is replay-safe, but its
        RESPONSE can still be lost after the mutation committed hub-side, and a raise outside
        the try would leak the toggle ON for the rest of the run.
        """
        async def _set(enabled: bool):
            return await session.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {"settings": {"publishOutputSchemas": enabled}, "confirm": True}})

        def _advertised(result) -> list[str]:
            return sorted(t.name for t in result.tools if t.outputSchema)

        # The prior value is read off the catalog rather than assumed: restore has to put back
        # what was there, and this is the same surface the restore is verified against.
        was_on = bool(_advertised(await session.list_tools()))
        try:
            flip = await _set(True)
            assert flip.isError is not True, (
                "could not turn publishOutputSchemas ON, so the SDK's validator has nothing to "
                f"referee: {flip.content!r}. hub_update_mcp_settings is developer-mode gated -- "
                "check enableDeveloperMode on this hub."
            )
            # A fresh list_tools is REQUIRED, not cosmetic: call_tool only refreshes the cache
            # for a name it has never seen, and the tools/list scenario above already cached
            # hub_get_info's schema as None.
            advertised = _advertised(await session.list_tools())
            assert "hub_get_info" in advertised, (
                "publishOutputSchemas reads ON but hub_get_info advertises no outputSchema, so the "
                f"SDK's validator has nothing to judge (advertised: {advertised[:5]}). The FLAT "
                "catalog always strips outputSchema -- if this hub runs useGateways=false, that is why."
            )
            # The referee: the SDK validates structuredContent against that schema with
            # jsonschema and raises if it does not conform. An assert here would be OUR check;
            # the value is that the raise comes from the SDK.
            result = await session.call_tool("hub_get_info", {})
            assert result.isError is not True, f"hub_get_info reported a tool error: {result.content!r}"
            # Reached only if the SDK skipped validation entirely (it raises on a schema-advertising
            # tool whose result has no structuredContent), which is the silent-no-op this scenario
            # exists to catch -- the whole point is that this branch used to be dead.
            assert result.structuredContent, \
                "no structuredContent and no SDK raise -- the validation path did not run"
            print(f"         SDK validated structuredContent against hub_get_info's advertised "
                  f"outputSchema ({len(advertised)} tools advertising)")
        finally:
            restored = False
            last: Exception | None = None
            for _ in range(3):
                try:
                    await _set(was_on)
                    if bool(_advertised(await session.list_tools())) == was_on:
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
        wrong = [p["mcp-protocol-version"] for p in posts if p["mcp-protocol-version"] != self.negotiated]
        assert not wrong, f"expected every header to be the negotiated {self.negotiated!r}, saw {sorted(set(wrong))}"
        modern_only = [p for p in posts if p["mcp-method"] is not None or p["mcp-name"] is not None]
        assert not modern_only, (
            "the SDK sent Mcp-Method/Mcp-Name, which exist only in 2026-07-28 -- this scenario's "
            f"premise no longer holds and the SDK pin's era must be re-read: {modern_only}"
        )
        # "Served" is the half that matters and it has to be READ, not inferred: an era-detection
        # regression 400s every one of these, and a scenario that only inspects the REQUEST hook
        # would still pass and print "all served" while every other scenario failed.
        answered = self.trace.answered_posts_after_initialize()
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


async def _main_async(config: dict) -> int:
    trace = RequestTrace()
    # safe_endpoint, never config['endpoint'] -- the live URL carries the access token.
    print(f"Connecting the official MCP Python SDK client to {config['safe_endpoint']} ...")
    # `streamable_http_client` is the current transport entry point; its predecessor
    # `streamablehttp_client` is @deprecated in the pinned SDK. It takes a caller-owned
    # httpx.AsyncClient rather than the old headers/timeout/factory parameters -- and
    # deliberately does NOT close a client it did not create, hence the outer `async with`.
    async with trace.build_client() as http_client:
        async with streamable_http_client(
            config["endpoint"], http_client=http_client
        ) as (read_stream, write_stream, _get_session_id):
            async with ClientSession(read_stream, write_stream) as session:
                scenarios = Scenarios(config, trace)
                ok = await scenarios.run_all(session)

    passed = sum(1 for _, good, _ in scenarios.results if good)
    total = len(scenarios.results)
    print(f"\n{'=' * 60}\nSDK conformance: {passed}/{total} scenarios passed "
          f"(mcp=={pinned_sdk_version()})\n{'=' * 60}")
    if not ok:
        for name, good, err in scenarios.results:
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
