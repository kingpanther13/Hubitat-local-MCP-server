# Compile-static HPM experiment

Scope: issue #529's dynamic-call-site experiment only. Base commit:
`93b78e36a78f34794a856d596fcbf3351a991d7b` (4.7.1).

All compilation, tests, release builds, and bytecode measurements run in GitHub
Actions on `experiment/529-compile-static`. Nothing is tested or compiled locally.
The personal hub is used only for the explicitly authorized compatibility probe,
isolated clone installation/update timing, and runtime checks.

## Compatibility

An inert app with an annotated integer addition method compiled on the C-8 running
2.5.2.135 and rendered `Result: 5 (expected 5)`. A negative control returning a
String from that annotated integer method failed with a static type-checking error.
See `probe-results.json`. Acceptance alone does not prove every selected method is
sandbox-compatible at runtime.

## Selection and validation

`measure.groovy` considers declared methods independently. It excludes overloaded
names, reflection/metaclass hooks, and nondeterministic runtime APIs. Standalone
static compilation rejects undeclared Hubitat bindings and cross-method calls.
Only methods with a measured reduction in dynamic dispatch are selected; signatures
and bodies are preserved. Reviewed helpers and catalog builders have direct
dynamic-versus-static output comparisons. The evidence distinguishes these from
methods covered only by the repository suite.

The experiment records whole-app dynamic call sites, dispatch instructions,
generated class count/size, and parent constant-pool size. The normal 64,000-entry
constant-pool limit applies. Verify mode checks that source annotations match the
selected list and that its bytecode measurements match the candidate.

Before installing either clone version, all 11 non-E2E validation workflows and
the experiment's measurement/clone parse checks must pass at that exact SHA.
No PR or E2E workflow is needed for this experiment.

## HPM package and timing

The CI-built inlined release app is renamed to `BAT529 MCP Rule Server Static`,
with child `BAT529 MCP Rule Static`, namespace `codex.bat529`, independent package
component IDs, and experiment-only versions. Production version files and HPM's
production package tracking are unchanged.

The clone manifest uses immutable source-SHA URLs. Its stable manifest URL lets
HPM detect the next experiment version. Use HPM's install-from-URL and update flows,
first through MCP tools, with the user-authorized headless browser fallback only
if necessary. A demonstrated HPM-tool gap should be filed using the repository's
issue template.

Record HPM start/completion and parent/child stage timestamps separately. The
production update immediately before this experiment took 245.245 seconds for
the parent stage and 266.050 seconds overall. That is a historical same-hub
comparison, not a controlled repeated benchmark. Initial installation and update
are distinct measurements; installation speed alone cannot establish an update
improvement. A version-only update measures HPM's update path without an additional
functional change.
