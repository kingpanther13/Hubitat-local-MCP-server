# MRTR Slow Writes — Implementation Plan

> Execute inline in the isolated worktree. Do not run the full local Gradle
> suite; one focused three-spec run was permitted and completed, with the full
> matrix delegated to manually dispatched GitHub fast workflows. Do not cancel
> an E2E run after it starts.

**Goal:** Replace PR #378's public `opToken`/deployment-job continuation protocol
with MCP 2026-07-28 request-to-request continuation for slow Rule Machine and
native-app writes, while preserving #378's independent fixes.

**Branch:** `feat/mrtr-slow-writes`, registered by `gh stack` above
`issue-376-deployment` / PR #378.

**Specification:**
`docs/superpowers/specs/2026-08-12-mrtr-slow-writes-design.md`.

## Task 1 — Lock down protocol contracts with failing tests

Files:

- Modify/create focused Spock specs under `src/test/groovy/server/`.
- Modify schema/conformance fixtures only where the final vendored July schema
  requires it.

Steps:

- [ ] Add dispatch tests for a modern eligible call returning a state-only
      `InputRequiredResult`.
- [ ] Add resume tests proving original arguments are not rerun.
- [ ] Add mismatch, expiry, duplicate, cleanup, and final aggregation cases.
- [ ] Add a legacy request case preserving its existing remainder envelope.
- [ ] Add package immediate-acknowledgement/duplicate cases.
- [ ] Commit the red tests and dispatch the smallest applicable remote fast lane
      to record the expected failure if the workflow supports branch dispatch.

## Task 2 — Add the internal request-state store

Files:

- Modify `hubitat-mcp-server.groovy`; keep the small request-boundary state
  helpers beside the dispatcher rather than adding another included library.

Steps:

- [ ] Implement opaque random handles and versioned bounded `atomicState` records.
- [ ] Implement canonical original-argument binding.
- [ ] Implement active fingerprint locks, TTL/cap sweeps, validated lookup,
      checkpoint update, and terminal cleanup.
- [ ] Keep state and raw arguments out of routine logs.

## Task 3 — Route MRTR at the tool-call boundary

Files:

- Modify `hubitat-mcp-server.groovy` around `handleToolsCall`, gateway resolution,
  and result framing.

Steps:

- [ ] Extract `params.requestState` independently of tool arguments.
- [ ] Gate MRTR on the final July protocol and an eligible leaf tool.
- [ ] On a first resumable slice, persist its continuation and return
      `resultType: "input_required"` plus request state.
- [ ] On resume, validate the binding and execute only stored continuation args.
- [ ] On terminal response, aggregate, clean state/lock, and return one normal
      `tool_result`.
- [ ] Reject unknown/mismatched/expired state without running a write.
- [ ] Reject a duplicate fresh call without disclosing or advancing state.

## Task 4 — Adapt each slow native/rule write

Files:

- Modify the existing handlers/adapters for `hub_set_rule`,
  `hub_set_native_app`, `hub_call_rule`, `hub_clone_native_app`, and
  `hub_import_native_app`.
- Modify their focused specs.

Steps:

- [ ] Map step-walker, bulk trigger/action, patch, and batch-call remainder
      envelopes to internal continuation arguments.
- [ ] Preserve prior slice errors, identifiers, and warnings in the final result.
- [ ] Define safe clone/import stage checkpoints and fail explicitly when a
      committed stage is ambiguous.
- [ ] Confirm legacy calls still receive the existing immediate remainder result.

## Task 5 — Remove #378's superseded public protocol

Files:

- Modify tool schemas/descriptions, server dispatch, guides, and focused tests.
- Remove the deployment-job library and its include/test wiring if it has no
  remaining independent use.

Steps:

- [ ] Remove public `opToken` inputs and auto-token output fields.
- [ ] Remove token polling/replay, recent-operation journal, terminal replay, and
      their public documentation/tests.
- [ ] Remove public deployment arguments/job APIs and their dedicated engine.
- [ ] Preserve and explicitly inventory all unrelated #378 Boy Scout fixes.
- [ ] Inspect the upper diff against `issue-376-deployment` to ensure it does not
      accidentally revert those fixes.

## Task 6 — Make package deployment asynchronous at its public boundary

Files:

- Modify `libraries/mcp-self-admin-lib.groovy` and focused specs/docs.

Steps:

- [ ] Keep `dryRun` synchronous.
- [ ] Validate/persist a real deploy request, acquire the existing in-flight
      guard, schedule work, and immediately return `status: "in_progress"`.
- [ ] Keep completion/failure visible through `hub_get_info.lastSelfDeploy`.
- [ ] Reject concurrent deploy requests server-side.

## Task 6b — Preserve the global all-write concurrency cap

Files:

- Modify `hubitat-mcp-server.groovy`, `libraries/mcp-self-admin-lib.groovy`, and
  focused specs/docs.

Steps:

- [ ] Acquire a lease before every actual non-MRTR write and release it in a
      `finally` block.
- [ ] Count active MRTR records and the asynchronous package worker too.
- [ ] Exclude only read-only, gateway-catalog, schema-only, device-replace
      list-options, package dry-run, and hub LED-identification calls.
- [ ] Prove parallel device-command refusal and ordinary-write lease cleanup.

## Task 7 — Add official SDK and live-relay coverage

Files:

- Modify `tests/sdk_conformance_test.py` or add one focused official-SDK test.
- Modify the focused hub E2E scenario and BAT documentation.

Steps:

- [ ] Pin official Python SDK `mcp==2.0.0` and exercise automatic state-only
      MRTR rounds through the high-level `mcp.client.Client.call_tool()` API;
      do not use `ClientSession.call_tool()` or a project-owned continuation loop.
- [ ] Assert a single logical call receives a final result after multiple HTTP
      requests.
- [ ] Use the official Streamable HTTP transport with observer-only redacted
      HTTP timing hooks; assert the logical call exceeds 10 seconds, each leg
      stays below the relay deadline, and the v2 result is `complete`.
- [ ] Add one modern path E2E; do not duplicate it for the legacy fallback.
- [ ] Keep the test deterministic and safely idempotent on the fixture hub.

## Task 8 — Remote verification before opening the PR

Steps:

- [ ] Commit and push the upper branch without opening a PR.
- [ ] Manually dispatch all fast workflows on the exact branch head:
      `unit-tests.yml`, `groovy2x-spock.yml`, `groovy24-parse.yml`,
      `python-tests.yml`, `ruff.yml`, `sandbox-lint.yml`, `lane-gate-test.yml`,
      `lease-scripts-test.yml`, `self-deploy-recovery.yml`, and `pr-guard.yml`.
- [ ] Let every started run reach a terminal conclusion; never cancel E2E.
- [ ] Fix failures and redispatch affected fast lanes until all ten are green for
      the current head.
- [ ] Deploy the branch artifact to cramehub and run the official SDK cloud-relay
      scenario across more than ten seconds of aggregate hub work.

## Task 9 — Open and monitor the native stacked draft PR

Steps:

- [ ] Only after all fast lanes are green, open a draft PR with base
      `issue-376-deployment` using the repository template.
- [ ] Link it with PR #378 using native `gh stack` and verify the GitHub Stack.
- [ ] Monitor all CI, including E2E, to terminal status without cancellation.
- [ ] Read CodeRabbit's entire top-level comments, review summaries, inline
      comments, outside-diff/walkthrough findings, follow-ups, and thread state.
- [ ] Validate each finding technically, implement in-scope fixes, push, and let
      every newly started run finish.
- [ ] Stop with the draft PR green and CodeRabbit feedback addressed. Do not merge
      or close either PR.

## Task 10 — Integration hardening from independent concurrency audit

Files:

- Modify `hubitat-mcp-server.groovy`, the package-deploy implementation, focused
  Spock specs, and only directly affected docs/fixtures.

Steps:

- [ ] Restore the `slow_ops` guide as a valid triple-single-quoted map value;
      `python tests/sandbox_lint.py` must parse the guide map again.
- [ ] Replace check-then-insert admission with one Hubitat-safe serialized or
      atomic reservation boundary covering duplicate detection, global capacity,
      and record/lease creation. Concurrent cap-one device writes must never both
      dispatch; concurrent identical MRTR preflights must never both be admitted.
- [ ] Claim each active `requestState` generation before executing its stored
      slice, so two concurrent repeats cannot execute the same checkpoint twice.
      A contender must fail/retry safely without advancing or restarting work.
- [ ] Never evict an active MRTR record to enforce the 16-record storage cap.
      Evict expired/terminal records first, clean active resources on expiry, and
      refuse a new preflight when active records leave no safe room.
- [ ] Correct mixed-mode cap classification: `hub_get_metrics` counts only when
      `recordSnapshot=true`; `hub_update_firmware(statusOnly=true)` is read-shaped;
      `hub_get_info(identifyHub=true)` remains deliberately exempt.
- [ ] Keep a package deployment counted/duplicate-locked until its own worker
      clears its marker (or the marker reaches the explicit stale timeout); an
      unrelated newer `lastSelfDeploy` timestamp must not release it.
- [ ] Add focused concurrency, mixed-mode, record-cap, and package-marker identity
      regression tests. Do not run the full local Gradle suite; use remote fast CI
      for Groovy coverage after the quick Python/sandbox checks pass.
- [ ] Preserve every unrelated #378 Boy Scout fix and commit the complete current
      integration cleanup on the stacked feature branch.
