"""Exercise the pinned SDK's retry/resume APIs without a hub or network socket.

The scripted HTTP peer establishes client behavior only. MrtrContinuationSpec
independently proves the real server retains ownership and rejects duplicate work.
"""

import json
from contextlib import asynccontextmanager
from importlib.metadata import version
from pathlib import Path

import anyio
import httpx2
import pytest
from mcp.client import Client
from mcp.client._input_required import InputRequiredRoundsExceededError
from mcp.client.streamable_http import streamable_http_client
from mcp_types import CallToolResult, InputRequiredResult

TOOL = "hub_set_rule"
ARGUMENTS = {"appId": 321, "confirm": True, "settings": {"description": "edit"}}
STATE = "opaque-state/+=="


class PendingPeer:
    """A fixed-binding, state-only peer; it performs no application mutations."""

    def __init__(self, pending_calls):
        self.pending_calls = pending_calls
        self.calls = []

    def handle(self, request):
        if request.method != "POST":
            return httpx2.Response(405)
        message = json.loads(request.content)
        if message["method"] == "tools/list":
            result = {
                "resultType": "complete", "ttlMs": 0, "cacheScope": "public",
                "tools": [{"name": TOOL, "inputSchema": {"type": "object"}}],
            }
        else:
            assert message["method"] == "tools/call"
            params = message["params"]
            assert params["name"] == TOOL
            assert params["arguments"] == ARGUMENTS
            assert request.headers["MCP-Protocol-Version"] == "2026-07-28"
            self.calls.append(params)
            if len(self.calls) > 1:
                assert params["requestState"] == STATE
            if len(self.calls) <= self.pending_calls:
                result = {"resultType": "input_required", "requestState": STATE}
            else:
                result = {
                    "resultType": "complete",
                    "content": [{"type": "text", "text": '{"success":true}'}],
                }
        return httpx2.Response(200, json={
            "jsonrpc": "2.0", "id": message["id"], "result": result,
        })


@asynccontextmanager
async def connected(peer, **options):
    # MockTransport handles every request; no resolver or socket can reach this URL.
    async with httpx2.AsyncClient(transport=httpx2.MockTransport(peer.handle)) as http:
        transport = streamable_http_client("https://mrtr.invalid/mcp", http_client=http)
        async with Client(transport, mode="2026-07-28", cache=None, **options) as client:
            yield client


def test_sdk_version_matches_the_conformance_pin():
    pin = next(line.removeprefix("mcp==") for line in
               Path(__file__).with_name("sdk-conformance-requirements.txt").read_text().splitlines()
               if line.startswith("mcp=="))
    assert version("mcp") == pin


def test_default_limit_exhausts_without_returning_the_request_state():
    async def scenario():
        peer = PendingPeer(pending_calls=100)
        async with connected(peer) as client:
            assert client.input_required_max_rounds == 10
            with pytest.raises(InputRequiredRoundsExceededError) as caught:
                await client.call_tool(TOOL, ARGUMENTS)
            assert caught.value.max_rounds == 10
            assert not hasattr(caught.value, "request_state")
            assert len(peer.calls) == 11  # initial call plus ten automatic retries
            assert "requestState" not in peer.calls[0]
            assert all(call["requestState"] == STATE for call in peer.calls[1:])

    anyio.run(scenario)


def test_configured_higher_limit_completes_the_same_long_exchange():
    async def scenario():
        peer = PendingPeer(pending_calls=12)
        async with connected(peer, input_required_max_rounds=20) as client:
            result = await client.call_tool(TOOL, ARGUMENTS)
            assert isinstance(result, CallToolResult)
            assert json.loads(result.content[0].text) == {"success": True}
            assert len(peer.calls) == 13

    anyio.run(scenario)


def test_captured_preflight_state_resumes_after_sdk_exhaustion():
    async def scenario():
        peer = PendingPeer(pending_calls=100)
        async with connected(peer) as client:
            first = await client.session.call_tool(TOOL, ARGUMENTS, allow_input_required=True)
            assert isinstance(first, InputRequiredResult)
            assert not first.input_requests
            saved_state = first.request_state
            assert saved_state == STATE
            with pytest.raises(InputRequiredRoundsExceededError):
                await client.call_tool(TOOL, ARGUMENTS, request_state=saved_state)
            assert len(peer.calls) == 12

            # The server can finish while the client is no longer polling.
            peer.pending_calls = len(peer.calls)
            resumed = await client.call_tool(TOOL, ARGUMENTS, request_state=saved_state)
            assert isinstance(resumed, CallToolResult)
            assert json.loads(resumed.content[0].text) == {"success": True}
            assert len(peer.calls) == 13
            assert all(call["requestState"] == saved_state for call in peer.calls[1:])

    anyio.run(scenario)
