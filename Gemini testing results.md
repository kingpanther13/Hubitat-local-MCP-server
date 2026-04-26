# Gemini Testing Results — Rule Machine Native CRUD (Phase 2)

This document compiles the testing results and technical observations discovered during the evaluation of PR #134 using the Bot Acceptance Test (BAT) suite.

**⚠️ DISCLAIMER:** The following findings were discovered by Gemini. They MUST be verified by a human developer. There is a high risk of hallucination or confabulation regarding internal hub behavior and endpoint responses.

---

## 1. Section 1: CRUD Basics + Rule Structure (T300–T316)
**Status: 100% PASS**

### Key Successes
- Minimal empty rule creation and renaming.
- Textarea comments round-trip with exact whitespace.
- Flag toggles (`useST`, `isFunction`, `dValues`) and multi-value logging enums round-trip.
- Soft and Force delete lifecycles work as expected.
- Runtime verbs (`pause`, `resume`, `start`, `stop`, `runActions`) executed successfully.

---

## 2. Section 2: Triggers & Conditions (T320–T349)
**Status: 43% PASS (13/30)**

### Key Technical Failures
1. **Time/Schedule Orchestration:** All tests involving `Certain Time`, `Periodic Schedule`, `Sunrise/Sunset`, or `Days of Week` restrictions failed to save their parameters. The tool creates the trigger but leaves the time/schedule fields blank/null.
2. **Condition Wizard Stalls:** Triggers that require an attached condition (Conditional Triggers) only reach the `rCapab` selection step. The subsequent condition-specific fields (`rDev`, `state`, etc.) are never written.
3. **Missing Capability Mapping:** High-level support is missing for `Gas`, `Shock`, `Sound`, `Time since event`, `HSM status`, and `Variable` triggers.
4. **Cross-Rule & Dynamic Data:** `Rule paused` triggers fail to save the target `ruleId`, and HTTP endpoints fail to generate/return the Local/Cloud URLs.
5. **Sticky Triggers:** The `stays` parameter is currently ignored for all device triggers.

---

## 3. Section 3: Actions (First 10 Tests, T350–T359)
**Status: 50% PASS (5/10)**

### Key Technical Observations
1. **Per-Mode Failures:** Actions using `perMode` (Switches, Buttons, Dimmers, CT) consistently fail to save their mode-keyed settings. The tool creates the action entry but leaves the per-mode maps empty.
2. **Color/CT Gaps:** High-level support for RGBW `color` and `colorTemp` actions is inconsistent; many parameters (hue, saturation, Kelvin) are lost during bulk creation.
3. **Successes:** Basic on/off/toggle/flash, dimmer set/adjust/fade, and shade/fan orchestration are working well for single-device and multi-device inputs.

---

## 4. Section 4: Expressions & Control Flow (Random Sample)
**Status: 0% PASS (0/3 sampled)**

### Key Technical Observations
1. **Missing Orchestration for Logic:** Required Expressions and IF/THEN/ELSE blocks are not currently map-able via high-level tools.
2. **Partial Commit Risks:** When a complex action (like a conditional trigger) partially fails, it leaves the rule in an inconsistent UI state (e.g., 'ONLY IF()' with no condition).

---

## 5. Technical Deep-Dive: The 'Navigation Wall'

Attempts to bypass the MCP tools and replicate Rule Machine orchestrations via direct `curl` (PowerShell `Invoke-RestMethod`) revealed why complex actions are failing.

1. **Internal State Isolation:** Rule Machine maintains a strict internal 'wizard state' (e.g., `state.actNdx`, `state.doActN`) per app ID. Even successful POSTs to initiate an action (`moreAct`) often return the `mainPage` JSON instead of the expected `doActPage` if the internal state is not perfectly primed.
2. **Sequential Schema Enforcement:** The hub's API layer enforces the same 'wizard dance' as the UI. Setting `actType` or `actSubType` is a prerequisite for the next level of settings (like `hue`, `saturation`, or `perMode` maps) to even exist in the accepted schema.
3. **Silent Drops:** Settings submitted via direct POST that are not part of the *currently active* wizard page are silently ignored by the hub. This is why bulk `create_native_app` payloads often result in 'empty' actions for anything more complex than a basic Switch on/off.

### Recommendation for Refactoring
The refactored tools (from Issue #137) will likely need to implement a more robust 'Walk-and-Wait' pattern:
- Write the category/type.
- Fetch the new schema.
- Write the subtype.
- Fetch the new schema.
- Write the final parameters.
