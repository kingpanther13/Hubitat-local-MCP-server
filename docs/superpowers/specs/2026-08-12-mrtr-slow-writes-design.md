# Design — MCP Request-To-Request (MRTR) continuation for slow writes

Status: **Approved for implementation** by the maintainer on 2026-08-12.
Base: stacked above PR **#378** (`issue-376-deployment`) on branch
`feat/mrtr-slow-writes`.

## 1. Problem

Hubitat's cloud relay commonly ends an HTTP request after roughly ten seconds.
Some write tools continue running successfully on the hub after that boundary,
but the Streamable HTTP client sees a timeout or 504 and can incorrectly retry a
write that already happened.

PR #377 improved response handling for issue #376. PR #378 adds an `opToken`
journal, polling/replay behavior, and a durable deployment-job protocol. Those
mechanisms recover some ambiguous calls, but they require clients to understand
a project-specific optional argument and leave two public continuation protocols
to maintain.

MCP 2026-07-28 defines request-to-request continuation for a tool call. A server
may return `InputRequiredResult` with `resultType: "input_required"` and an opaque
`requestState`. A compatible client repeats the same `tools/call`, including the
returned `requestState`; the server eventually returns one ordinary
`CallToolResult`. Streamable HTTP remains the transport. Each continuation round
is a separate HTTP request, so the cloud relay gets a fresh request budget.

## 2. Goals

- Use MCP 2026-07-28 MRTR as the sole public continuation protocol for long
  native-app and Rule Machine writes.
- Keep Streamable HTTP and all existing installations working.
- Require no client plugin, task extension, app, or user-supplied token.
- Keep each cloud relay leg under its request deadline.
- Prevent a second fresh invocation from duplicating an active write.
- Preserve `maxConcurrentWrites` as a server-side cap around every actual write,
  including short device commands and parallel agent batches.
- Return one final success/failure tool result after all continuation legs.
- Preserve useful, independent fixes already present in PR #378.
- Keep a legacy-client fallback without maintaining a second tested protocol.
- Make package deployment acknowledge long work immediately and reject duplicate
  deploys while it is active.

## 3. Non-goals

- MCP Tasks. Tasks are a separately negotiated optional capability and are not
  needed for this design.
- Switching from Streamable HTTP to the obsolete standalone SSE transport.
- Recovery across a lost client turn, process restart, or arbitrary later chat.
- A general durable workflow engine or public deployment-job API.
- Exposing continuation internals as tool arguments.
- Retrying irreversible work whose completion cannot be determined safely.

## 4. Protocol behavior

### 4.1 Eligibility and negotiation

MRTR is enabled only when the request's negotiated protocol version is
`2026-07-28` or newer and the selected leaf tool is explicitly continuation
capable. Initial candidates are the existing budget-aware native-app and Rule
Machine writes:

- `hub_set_rule`
- `hub_set_native_app`
- `hub_call_rule`
- `hub_clone_native_app`
- `hub_import_native_app`

The gateway wrapper does not own continuation state. It resolves to a leaf tool,
and the leaf tool name is part of the state binding.

### 4.2 First round

The server performs a mutation-free preflight: validate access, check for an
identical active operation, reserve one global write slot, and persist the bound
request record. It does not enter the leaf handler. It returns only:

```json
{
  "resultType": "input_required",
  "requestState": "opaque-random-handle"
}
```

`inputRequests` is intentionally absent: there is no human input to collect.
The official SDK treats a state-only result as an automatic continuation and
resends the same tool name and original arguments with `requestState`.

### 4.3 Later rounds

On each resumed call, the server validates that the opaque state exists, is not
expired, and is bound to:

- the same negotiated protocol family;
- the same leaf tool;
- the same canonical original arguments; and
- the same active operation record.

The first resumed request executes the original first slice; later resumed
requests execute only the stored continuation, never an already-completed slice.
Another resumable result updates the record and returns `input_required` again.
A concurrent repeat while that exact generation is executing first waits a
bounded, transport-budget-aware interval for the owner to checkpoint or finish,
then atomically reclaims/replays within the same HTTP leg when possible. If the
owner remains live, it returns the same state-only `input_required` shape
without advancing the record. This pacing keeps an automatic client inside the
original logical call without a hot retry loop.
A terminal result removes the active record and returns one normal
`CallToolResult` with `resultType: "complete"`.

Unknown, expired, mismatched, or already-consumed request state fails loudly and
does not execute a write.

### 4.4 Opaque state and storage

`requestState` is a random lookup handle, not serialized arguments. Its
`atomicState` record contains:

- schema version;
- creation, update, and expiry timestamps;
- leaf tool name and a SHA-256 digest of every canonical client-visible
  argument (computed before any server-only timing field is injected);
- stored next arguments/checkpoint;
- accumulated slice outcome needed for the final result; and
- active/terminal status.

Records are bounded by a per-installation cap and TTL. A class-static execution
registry distinguishes a handler that is still running from abandoned persisted
state: a live generation cannot be swept merely because its recovery TTL passed,
while a JVM reload clears the registry and lets an abandoned record expire.
Expired records and their fingerprint locks are swept opportunistically. A terminal result is retained
briefly under the same opaque `requestState`, so loss of only the final HTTP
response can be replayed by the still-running logical call. This is not a public
or long-lived replay journal and does not support arbitrary cross-turn recovery.

### 4.5 Duplicate prevention

An active-operation index maps the leaf tool plus canonical original arguments
to the opaque state. A fresh call with the same fingerprint while continuation
is active does not receive the state handle and does not execute any write. It
returns a normal, non-terminal informational/error tool result saying that the
operation is already in progress. Only the call carrying the matching
`requestState` may advance it.

This is server-side idempotency for the active window, not a public polling
protocol.

### 4.6 Global write concurrency

`maxConcurrentWrites` applies independently of MRTR eligibility. Every actual
mutating call reserves a server-side lease before dispatch and releases it when
the handler finishes. Active MRTR records and the background package deployment
worker also consume slots. This protects the hub from rapid device-command spam
and parallel client/agent batches, not only from long writes.

Read-only tools, gateway catalog calls, schema-only probes,
`hub_call_device_replace(list_options=true)`, and
`hub_update_package(dryRun=true)` do not consume a slot. In particular,
`hub_get_info(identifyHub=true)` remains outside the write safeguards by design.
Leases contain no public token. The same class-static execution registry keeps a
running ordinary handler counted without a fixed-duration assumption; after a
JVM reload, only abandoned persisted leases age out by TTL.

### 4.7 Final result aggregation

Continuation adapters translate each handler's existing remainder envelope into
the next internal arguments. They also preserve prior slice errors, changed
identifiers, and warnings needed by the tool's published output contract. The
final response is a single normal result whose success/failure semantics describe
the whole logical operation, not just the last slice.

Clone/import adapters must checkpoint at safe stage boundaries. A stage that may
have committed before returning cannot be blindly reissued. If its outcome is
ambiguous, the operation terminates with an explicit partial/uncertain failure.

## 5. Legacy clients

Requests negotiated below MCP 2026-07-28 cannot use `requestState`. They keep the
existing remainder-bearing `status: "in_progress"` result where one already
exists. This fallback is backwards compatible but is not a second end-to-end
protocol and receives no new client token, journal, polling endpoint, or durable
job engine.

Public `opToken` inputs, auto-token response fields, token replay/polling, recent
operation journals, and deployment-job arguments introduced by PR #378 are
removed by this upper PR. Independent #378 bug fixes remain.

## 6. Package deployment exception

`hub_update_package` can run for minutes and may reload the app that is executing
it. Repeated state-only MRTR rounds are a poor fit because common clients cap the
number of automatic rounds.

For a real deployment, the tool validates and persists the request, schedules
the existing deployment work, and immediately returns a normal
`status: "in_progress"` result. The existing server-side deployment lock rejects
duplicates. Completion remains visible through the existing
`hub_get_info.lastSelfDeploy` status. `dryRun` remains synchronous. This requires
no client plugin or user-provided token.

## 7. Code organization

- A focused request-state library owns canonical binding, opaque handle creation,
  TTL/cap cleanup, active locks, resume validation, and terminal cleanup.
- `handleToolsCall` owns MCP request/result routing and extracts
  `params.requestState`.
- Leaf-specific adapters recognize current resumable envelopes and produce their
  next arguments/checkpoints.
- Tool handlers remain responsible for safe bounded slices; protocol framing is
  not scattered through them.
- Library include markers, test `LIBS`, and include-resolution fixtures stay in
  lockstep.

## 8. Error and cleanup rules

- Never resume when tool or original arguments differ.
- Never expose another caller's opaque state from a duplicate fresh call.
- Never silently restart after unknown or expired state.
- Clear active state on a definitive terminal response.
- Retain state after a transient response-serialization failure when safe, so a
  matching continuation can retry the same checkpoint.
- Convert adapter/storage failures into ordinary MCP tool errors; reserve
  JSON-RPC protocol errors for malformed requests.
- Redact request state and original arguments from routine logs.

## 9. Verification

Unit/dispatch coverage must include:

- modern first round, repeated round, and final result;
- state-only `InputRequiredResult` schema conformance;
- state/tool/argument/version mismatch and expiry;
- active duplicate rejection and terminal lock cleanup;
- state cap/TTL cleanup;
- result aggregation across multiple slices;
- legacy remainder fallback;
- clone/import safe-stage behavior;
- package immediate acknowledgement and duplicate lock; and
- gateway leaf routing.

An official Python MCP SDK test must prove that one logical `call_tool` follows
multiple `input_required` rounds over Streamable HTTP and receives the final
result. A live cramehub cloud-relay test must cross the old ten-second aggregate
boundary while every individual HTTP leg stays within budget.

## 10. Acceptance criteria

- [ ] No client-visible `opToken` or deployment-job protocol remains in the upper
      diff; independent #378 fixes are preserved.
- [ ] Modern clients automatically continue eligible slow writes without 504s
      caused solely by aggregate duration.
- [ ] Legacy clients still receive their current immediate remainder result.
- [ ] A fresh duplicate cannot advance or repeat an active operation.
- [ ] `maxConcurrentWrites` caps all actual writes, including short device
      commands, while documented read-shaped calls remain exempt.
- [ ] One final result represents the complete logical operation.
- [ ] Package deployment acknowledges immediately and rejects concurrent deploys.
- [ ] Official schema, SDK, unit, dispatch, sandbox, and focused live E2E checks
      pass.
- [ ] The PR remains a native GitHub Stack above #378.
