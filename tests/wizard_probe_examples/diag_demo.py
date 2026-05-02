#!/usr/bin/env python3
"""
Diag demo: Variant Y -- actionCancel vs actionDone after condActs+getIfThen write.

Investigation context (Issue #77):
  After addRequiredExpression completes, RM's atomicState.predCapabs still holds
  the condition-builder context.  A subsequent plain addAction then gets wrapped
  in an unintended "IF (**Broken Condition**) THEN" block.

  The fix is a "ghost ifThen" sequence on doActPage:
    1. Write actType=condActs + actSubType=getIfThen  (triggers RM's ifThen init,
       which re-initializes predCapabs as a side-effect)
    2. Click actionCancel  (discard the ghost action without committing it)

  This demo reproduces the Variant Y empirical finding that drove the fix:
    - actionCancel (this demo): predCapabs cleared, subsequent addAction is clean
    - actionDone (Variant Z): bakes an empty IF() block AND leaves predCapabs dirty

  Answer found: actionCancel is correct; actionDone is wrong even though both
  "complete" the ifThen flow.

Usage:
    cd Hubitat-local-MCP-server
    uv run --python 3.12 --with pyyaml tests/wizard_probe_examples/diag_demo.py

Expected output:
    broken: False
    render: (contains "On: <device>" with no "Broken Condition" prefix)
    errors: []
"""

import sys
from pathlib import Path

# Allow running from the repo root without installing the package
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tests.wizard_probe import HubitatMcpClient, load_config, quick_probe

# ---------------------------------------------------------------------------
# Replace these with a real device ID from your hub's device_pool
# or pass via DEVICE_ID env var.
# ---------------------------------------------------------------------------
import os
SWITCH_DEVICE_ID = int(os.environ.get("DEVICE_ID", "1063"))

config = load_config()
client = HubitatMcpClient(
    config["hub_url"], config["app_id"], config["access_token"], verbose=False
)
client.initialize()

result = quick_probe(
    client,
    name="variant_y_cancel_demo",
    steps=[
        # Step 1: add a Required Expression (leaves predCapabs dirty in RM state)
        {"addRequiredExpression": {"conditions": [
            {"capability": "Switch", "deviceIds": [SWITCH_DEVICE_ID], "state": "on"}
        ]}},
        # Step 2: ghost ifThen -- write condActs+getIfThen then cancel
        # This is what the fix does internally; exposed here as raw steps for inspection.
        {"raw_setting": {"page": "doActPage",
                         "settings": {"actType.1": "condActs",
                                      "actSubType.1": "getIfThen"}}},
        {"raw_button": {"page": "doActPage", "name": "actionCancel"}},
        # Step 3: plain addAction -- should be clean (no Broken Condition wrapper)
        {"addAction": {"capability": "Switch", "deviceIds": [SWITCH_DEVICE_ID],
                       "command": "on"}},
    ],
    verbose=True,
)

print()
print(f"broken: {result['final']['broken']}")
print(f"render:")
for line in result["final"]["paragraphs"]:
    print(f"  {line}")
print(f"errors: {result['errors']}")
print(f"status: {result['status']}  ({result['duration_s']}s)")
