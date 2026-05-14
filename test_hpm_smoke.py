# -*- coding: utf-8 -*-
import requests
import json
from datetime import datetime
import sys

HUB_URL = "http://hubitat-test.wep.net"
APP_ID = "38"

# Try to get access token by fetching the app's state page
def get_access_token():
    """Try to get the MCP app's access token from the hub."""
    try:
        # First, try to get app info (will need token, but let's see what happens)
        print("[INFO] Attempting to retrieve MCP app token...")
        return None  # For now, rely on the hub exposing unauthenticated endpoints
    except Exception as e:
        print(f"[WARN] Could not auto-discover token: {e}")
        return None

def call_mcp_tool(tool_name, sub_tool, args, token=None):
    """Call an MCP tool via the test hub endpoint."""
    url = f"{HUB_URL}/apps/api/{APP_ID}/mcp"
    payload = {
        "tool": tool_name,
        "args": {
            "tool": sub_tool,
            **(args or {})
        }
    }
    headers = {"Content-Type": "application/json"}
    if token:
        payload["access_token"] = token
    
    ts = datetime.now().strftime('%H:%M:%S')
    print(f"\n[{ts}] Calling {tool_name}/{sub_tool}")
    
    try:
        resp = requests.post(url, json=payload, headers=headers, timeout=10)
        print(f"  Status: {resp.status_code}")
        
        if resp.status_code == 200:
            data = resp.json()
            print(f"  OK - Response received ({len(json.dumps(data))} bytes)")
            return data
        else:
            txt = resp.text[:200]
            print(f"  FAIL - Status {resp.status_code}: {txt}")
            return {"error": f"HTTP {resp.status_code}", "text": txt}
    except Exception as e:
        print(f"  EXCEPTION: {e}")
        return {"error": str(e)}

print("=" * 70)
print("HPM Smoke Test - Round-2 Changes (Test Hub)")
print("=" * 70)

token = get_access_token()

# Test 1: list_hpm_packages
print("\n[SMOKE 1] list_hpm_packages")
r1 = call_mcp_tool("manage_hpm", "list_hpm_packages", {}, token)
if r1 and not r1.get("error"):
    packages = r1.get("packages", [])
    count = r1.get("count")
    print(f"  OK: count={count}, packages returned={len(packages)}")
    if packages:
        pkg = packages[0]
        print(f"    Sample: {pkg.get('name')} v{pkg.get('version')}")
else:
    print(f"  FAIL: {r1.get('error')}")

# Test 2: get_hpm_drift
print("\n[SMOKE 2] get_hpm_drift")
r2 = call_mcp_tool("manage_hpm", "get_hpm_drift", {}, token)
if r2 and not r2.get("error"):
    summary = r2.get("summary", "")
    total_signals = r2.get("totalDriftSignals", 0)
    drift_entries = r2.get("drift", [])
    orphan_enabled = r2.get("orphanDetection", {}).get("enabled")
    
    print(f"  OK")
    print(f"    Summary: {summary[:80]}...")
    print(f"    Total drift signals: {total_signals}")
    print(f"    Drift entries: {len(drift_entries)}")
    print(f"    Orphan detection enabled: {orphan_enabled}")
    
    # Consistency check
    entries_with_signals = sum(1 for d in drift_entries if d.get("signals"))
    if entries_with_signals == total_signals:
        print(f"    OK: {entries_with_signals} entries match totalDriftSignals")
    else:
        print(f"    WARN: {entries_with_signals} entries vs totalDriftSignals={total_signals}")
else:
    print(f"  FAIL: {r2.get('error')}")

# Test 3: packageFilter match
print("\n[SMOKE 3] get_hpm_drift with packageFilter")
r3 = call_mcp_tool("manage_hpm", "get_hpm_drift", {"packageFilter": "hubitat"}, token)
if r3 and not r3.get("error"):
    print(f"  OK - Got response")
    if r3.get("filterMatchedZero"):
        print(f"    Info: filterMatchedZero=true")
    else:
        drift = r3.get("drift", [])
        print(f"    Info: Matched {len(drift)} package(s)")
else:
    print(f"  FAIL: {r3.get('error')}")

# Test 4: packageFilter no-match fallback
print("\n[SMOKE 4] get_hpm_drift with bad packageFilter")
r4 = call_mcp_tool("manage_hpm", "get_hpm_drift", {"packageFilter": "definitely-no-such-package"}, token)
if r4 and not r4.get("error"):
    print(f"  OK - Got response")
    if r4.get("filterMatchedZero"):
        available = r4.get("availablePackages", [])
        print(f"    OK: filterMatchedZero=true, fallback list has {len(available)} packages")
    else:
        print(f"    WARN: Expected filterMatchedZero=true")
else:
    print(f"  FAIL: {r4.get('error')}")

print("\n" + "=" * 70)
print("Test complete")
print("=" * 70)
