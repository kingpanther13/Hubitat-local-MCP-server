#!/usr/bin/env python3
"""One-off: discover the platform 2.5.1 Visual Rules Builder graph schema.

Posts candidate graph definitions through hub_set_visual_rule and prints the hub's
own validationErrors for each, so the schema comes from the validator rather than
from guesswork. Every rule it creates is deleted again. Not for merge.
"""
import json
import os
import sys
import urllib.request

MCP_URL = os.environ["MCP_URL"]


def call(name, args, tool=None):
    params = {"name": name, "arguments": dict(args)}
    if tool:
        params["arguments"] = {"tool": tool, "args": args}
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": params}).encode()
    req = urllib.request.Request(MCP_URL, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = json.loads(r.read().decode())
    except Exception as exc:  # noqa: BLE001 - a probe reports, never raises
        return {"_transport_error": str(exc)}
    text = (payload.get("result", {}).get("content") or [{}])[0].get("text")
    if not text:
        return {"_no_text": json.dumps(payload)[:300]}
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"_unparsed": text[:300]}


def switch_id():
    r = call("hub_read_devices", {"capabilityFilter": "Switch", "limit": 1}, tool="hub_list_devices")
    devs = r.get("devices") or []
    return int(devs[0]["id"]) if devs else None


def probe(idx, label, definition):
    print("-" * 60)
    print(f"CANDIDATE {idx}: {label}")
    name = f"VRBPROBE_{os.environ.get('GITHUB_RUN_ID', 'local')}_{idx}"
    r = call("hub_manage_rule_machine", {"name": name, "confirm": True, "definition": definition},
             tool="hub_set_visual_rule")
    app_id = r.get("appId")
    print(f"  create: success={r.get('success')} appId={app_id} "
          f"fmt={r.get('format') or r.get('hubNativeFormat')}")
    for k in ("error", "_transport_error", "_unparsed", "_no_text"):
        if r.get(k):
            print(f"  {k}: {str(r[k])[:400]}")
    if not app_id:
        return
    h = call("hub_read_rules", {"appId": app_id}, tool="hub_get_rule_health")
    print(f"  broken={h.get('broken')} ruleFormat={h.get('ruleFormat')}")
    for err in h.get("validationErrors") or []:
        print(f"  ERR: {err}")
    rb = call("hub_read_rules", {"appId": app_id}, tool="hub_get_visual_rule")
    print(f"  readback.format={rb.get('format')}")
    print(f"  readback.definition={json.dumps(rb.get('definition'))[:500]}")
    call("hub_manage_rule_machine", {"appId": app_id, "confirm": True}, tool="hub_delete_native_app")


def main():
    sw = switch_id()
    print(f"probe switch id={sw}")
    if sw is None:
        print("::error::no switch device available")
        return 1

    candidates = [
        ("current e2e fixture (type + inline fields)", {
            "version": 1,
            "nodes": [
                {"id": "t1", "type": "trigger", "triggerCondition": "trigger", "triggerType": "switch",
                 "switches": [sw], "deviceIds": [sw], "switchEvent": "Turns off"},
                {"id": "a1", "type": "action", "actionType": "turnOff", "switches": [sw], "deviceIds": [sw]},
            ],
            "edges": [{"from": "t1", "to": "a1", "port": "next"}],
        }),
        ("kind + config only", {
            "version": 1,
            "nodes": [
                {"id": "t1", "kind": "trigger",
                 "config": {"triggerType": "switch", "deviceIds": [sw], "switchEvent": "Turns off"}},
                {"id": "a1", "kind": "action", "config": {"actionType": "turnOff", "deviceIds": [sw]}},
            ],
            "edges": [{"from": "t1", "to": "a1", "port": "next"}],
        }),
        ("kind + config + triggerMerge + decision", {
            "version": 1,
            "nodes": [
                {"id": "t1", "kind": "trigger",
                 "config": {"triggerType": "switch", "deviceIds": [sw], "switchEvent": "Turns off"}},
                {"id": "tm", "kind": "triggerMerge", "config": {}},
                {"id": "d1", "kind": "decision", "config": {}},
                {"id": "a1", "kind": "action", "config": {"actionType": "turnOff", "deviceIds": [sw]}},
            ],
            "edges": [
                {"from": "t1", "to": "tm", "port": "next"},
                {"from": "tm", "to": "d1", "port": "next"},
                {"from": "d1", "to": "a1", "port": "true"},
            ],
        }),
        # An empty graph: the validator's complaints enumerate what a minimal rule needs.
        ("empty graph (enumerate required nodes)", {"version": 1, "nodes": [], "edges": []}),
    ]
    for i, (label, definition) in enumerate(candidates, start=1):
        probe(i, label, definition)
    print("-" * 60)
    print("probe complete")
    return 0


if __name__ == "__main__":
    sys.exit(main())
