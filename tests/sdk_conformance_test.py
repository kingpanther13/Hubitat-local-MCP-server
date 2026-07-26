#!/usr/bin/env python3
"""
Hubitat MCP Server — conformance scenarios driven by the OFFICIAL MCP Python SDK.

The hub sandbox whitelists imports and cannot load jars, so this server's protocol layer
is hand-written. That makes an independent referee necessary: here the official `mcp`
package's client speaks Streamable HTTP to a real hub endpoint and exercises the REAL
negotiation path — initialize -> tools/list -> a benign tools/call — with every response
parsed through the SDK's own validators rather than through assertions written against
this repo's reading of the spec.

The companion leg is `src/test/groovy/server/McpWireSchemaConformanceSpec.groovy`, which
validates rendered wire payloads against the spec authors' vendored JSON Schemas.

What the SDK genuinely referees (as opposed to what this file asserts on top):
  * protocol-version negotiation — ClientSession.initialize() RAISES if the version the
    server echoes is not in the SDK's own SUPPORTED_PROTOCOL_VERSIONS;
  * the transport contract — the 202 a notification-only POST must answer with (the
    `notifications/initialized` POST goes through this path), and the fact that no GET
    SSE stream or DELETE is attempted because the server issues no session id;
  * result parsing — every InitializeResult / ListToolsResult / CallToolResult, including
    each advertised Tool's shape, goes through pydantic models.
The SDK's Python models are `extra="allow"`, so they do NOT reject unknown result keys.
The strict-unknown-key referee (the TypeScript SDK's `EmptyResultSchema`, i.e.
`ResultSchema.strict()`) is reproduced on the unit side by McpSchemaValidator's strict
view; the ping check below asserts the same property explicitly rather than pretending
the Python SDK caught it.

The load-bearing scenario is the LEGACY-HEADER one. Once initialize has negotiated, the
SDK stamps `mcp-protocol-version: <negotiated>` onto every subsequent POST and sends NO
Mcp-Method / Mcp-Name (those exist only in 2026-07-28). A server that read header
PRESENCE as "modern era" would answer every one of those with 400 + -32020 — i.e. break
every deployed client. This file captures the real outbound headers and asserts both that
the header was there and that the server served the request anyway.

NO SILENT SKIPS. A missing `mcp` package, a version other than the pin, or missing hub
config all FAIL with a remediation message. A skip here would look like coverage.

Configuration is shared with tests/e2e_test.py — `tests/e2e_config.json` (gitignored) or
HUBITAT_HUB_URL / HUBITAT_APP_ID / HUBITAT_ACCESS_TOKEN.

Usage:
    pip install -r tests/sdk-conformance-requirements.txt
    python tests/sdk_conformance_test.py
"""

from __future__ import annotations

import json
import sys
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

    `typing_extensions.deprecated` records its message on the callable as `__deprecated__`,
    so this reads the pinned SDK's own marker rather than a hand-maintained list of what
    was current when this file was written. `streamable_http_client` replaced
    `streamablehttp_client` inside the 1.x line, and shipping new code against a
    deprecated entry point of a pinned version is a defect -- so a pin bump that
    deprecates something used here fails the run and names the replacement, instead of
    emitting a DeprecationWarning nobody reads.

    Only the transport function and the ClientSession methods actually invoked are
    checked. Deprecated `@overload`s are invisible here (the decorator sits on the
    overload, not the implementation) -- `list_tools()` is called with no arguments, which
    binds to the undecorated no-arg overload, not the deprecated positional-`cursor` one.
    """
    checked = [("streamable_http_client", streamable_http_client)] + [
        (f"ClientSession.{name}", getattr(ClientSession, name))
        for name in ("initialize", "send_ping", "list_tools", "call_tool")
    ]
    return [f"{label}: {fn.__deprecated__}"
            for label, fn in checked if getattr(fn, "__deprecated__", None)]


def _describe_exception(exc: BaseException) -> str:
    """`type: message`, flattening anyio's ExceptionGroup wrappers.

    A transport failure surfaces as "unhandled errors in a TaskGroup", which names nothing
    actionable -- the real cause (ConnectError, ReadTimeout, an HTTP status error) is a
    sub-exception. Recurse so the printed line says what actually went wrong.
    """
    if isinstance(exc, BaseExceptionGroup):
        return "; ".join(_describe_exception(sub) for sub in exc.exceptions)
    return f"{type(exc).__name__}: {exc}"


def _load_hub_config() -> dict:
    """Reuse tests/e2e_test.py's config resolution and endpoint-URL construction verbatim.

    Imported lazily because it needs this file's directory on sys.path first, and because
    a `--help`-style invocation should not pay for importing the whole e2e runner.
    The cloud-vs-LAN URL shape is a genuine trap (the cloud form already carries
    /api/<UUID>/, so the app path differs) -- deriving it from HubitatMcpClient means the
    two legs can never disagree about where the endpoint is.
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
    return {
        "endpoint": client.endpoint,
        "initialize_versions": e2e_test.INITIALIZE_PROTOCOL_VERSIONS,
        "supported_versions": e2e_test.SUPPORTED_PROTOCOL_VERSIONS,
    }


class RequestTrace:
    """Records the method and MCP-relevant headers of every outbound request.

    Observed through an httpx `event_hooks["request"]` hook on the AsyncClient this file
    owns and hands to `streamable_http_client`. The hook receives the fully-built
    `httpx.Request` AFTER httpx has merged the client's headers with the per-request ones
    the SDK's transport supplies, so what lands here is what the SDK actually put on the
    wire -- not a reconstruction of it. That is what lets the legacy-header scenario
    assert on the header rather than assume it.
    """

    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []

    async def record(self, request: httpx.Request) -> None:
        self.requests.append({
            "method": request.method,
            "mcp-protocol-version": request.headers.get("mcp-protocol-version"),
            "mcp-method": request.headers.get("mcp-method"),
            "mcp-name": request.headers.get("mcp-name"),
            "mcp-session-id": request.headers.get("mcp-session-id"),
            "accept": request.headers.get("accept"),
        })

    def build_client(self) -> httpx.AsyncClient:
        """The AsyncClient to hand `streamable_http_client` as `http_client=`.

        The modern transport takes a caller-owned client instead of the old
        `httpx_client_factory` hook, so HTTP configuration lives here now. These values
        reproduce what the SDK's own (now-deprecated) wrapper built: `follow_redirects`
        plus an `httpx.Timeout(<request>, read=<sse>)` pair. The read timeout is inert
        against this server -- it answers `application/json` and issues no session id, so
        the SDK opens no GET SSE stream and sends no DELETE on close -- but it is kept at
        the SDK's own default rather than silently tightened.

        NOTE: `streamable_http_client` does NOT manage a caller-supplied client's
        lifecycle, so the caller must use this inside `async with`.
        """
        return httpx.AsyncClient(
            follow_redirects=True,
            timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS, read=SSE_READ_TIMEOUT_SECONDS),
            event_hooks={"request": [self.record]},
        )

    def posts_after_initialize(self) -> list[dict[str, Any]]:
        """Every POST that carried a protocol-version header — i.e. everything the SDK sent
        after initialize told it which revision was negotiated."""
        return [r for r in self.requests
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
            self._record(name, _describe_exception(exc))
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
        await self._run("every post-initialize POST carried the legacy MCP-Protocol-Version header "
                        "and was served",
                        self._legacy_header_contract)
        return all(ok for _, ok, _ in self.results)

    async def _initialize(self, session: ClientSession) -> None:
        # ClientSession.initialize() requests types.LATEST_PROTOCOL_VERSION and RAISES
        # RuntimeError if the echoed version is outside the SDK's SUPPORTED_PROTOCOL_VERSIONS
        # -- so simply getting a result back is the SDK refereeing our negotiation. It then
        # sends notifications/initialized, a notification-only POST the transport requires be
        # answered 202.
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
        assert payload.get("success") is not False, f"hub_get_info returned a failure envelope: {payload}"
        print(f"         hub_get_info returned {len(block.text)} chars of JSON, keys={sorted(payload)[:6]}")

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
        # Every scenario above already succeeded through these same requests, so the server
        # served them; state that explicitly rather than leaving it implied.
        print(f"         {len(posts)} legacy-versioned POST(s) sent with no Mcp-Method/Mcp-Name, all served")


async def _main_async(config: dict) -> int:
    trace = RequestTrace()
    print(f"Connecting the official MCP Python SDK client to {config['endpoint'].split('?')[0]} ...")
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

    print("=" * 60)
    print(f"Hubitat MCP Server — official-SDK conformance (mcp=={pin})")
    print("=" * 60)
    try:
        code = anyio.run(_main_async, config)
    except Exception as exc:
        # Report the transport failure as one actionable line rather than dumping an anyio
        # exception-group traceback -- an unreachable hub is the common case here.
        print(f"\nERROR: the SDK client could not complete a session against the hub: "
              f"{_describe_exception(exc)}")
        print("  Check that the hub is online, the MCP endpoint URL and access token are correct, "
              "and that the endpoint answers Streamable HTTP POSTs.")
        sys.exit(1)
    sys.exit(code)


if __name__ == "__main__":
    main()
