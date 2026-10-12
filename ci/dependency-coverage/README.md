# Dependency coverage experiment

This is disposable instrumentation for `experiment/dependency-coverage-20261012`.
The PR candidate remains `experiment/dependency-compat-20261012`, containing only
the five dependency version changes. No application code or assertions are changed.

- Baseline declarations: `38115a634b3853c474f093da93c5c9240aa32b1e`.
- Candidate declarations and identical application/tests: `78115a5a59bc922adfab4ed02a690bbd874ed4bf`.
- Five lanes: Groovy 3 normal/strict, each with gateways on/off; Groovy 2.5 strict.
- Both versions run on the experiment branch. Nothing runs on or writes to main.

Dispatch `unit-tests.yml` on this branch with `scope=smoke` or `scope=full`.
Download all `coverage-*` artifacts, then run:

```
python3 ci/dependency-coverage/compare.py DOWNLOADED_ARTIFACTS full
```

The agent captures dynamically compiled server/libraries, rule app and watchdog
classes. Application classes are distinguished from Groovy's generated call-site
adapters by their source-file metadata. Each Spock iteration gets its own probe
snapshot spanning setup, test body and cleanup. Execution outside iterations is
preserved separately and merged into the suite report. The existing builds are
single-threaded; this collection assumes that configuration.

Comparison requires matching bytecode identities, class inventory, line/branch/
instruction counters, aggregate probe bitsets, per-iteration probe bitsets, and
JUnit test identities/outcomes/multiplicities. Every passing JUnit case must have
a matching iteration snapshot, and every expected lane must be present. Raw exec
files and original class dumps remain available for investigating differences.
Matching probes establish matching measured coverage, not identical values,
execution order, timing, or correctness of behavior the tests never assert.

## Instrumentation size limitation

Stock JaCoCo 0.8.15 overflows the large inlined server's constant pool. The original
Groovy 2.5 server class already has 59,489 constant-pool entries. This experiment
changes only JaCoCo's integer-push encoding: large probe indexes use a shared high
part and an immediate low part instead of a separate constant-pool entry each.
Probe identities and placement remain unchanged. A runner-side JVM self-check
checks integer values and the required stack bound before running the suite.
Both dependency sets use exactly the same instrumentation and 2 GiB test heap.

`vendor/InstrSupport.java` comes from [JaCoCo v0.8.15](https://github.com/jacoco/jacoco/blob/v0.8.15/org.jacoco.core/src/org/jacoco/core/internal/instr/InstrSupport.java),
copyright Mountainminds GmbH & Co. KG and contributors, EPL-2.0; its header is
retained. `prepare_agent.py` applies the documented encoding change when preparing
the agent. This adjustment is confined to the measurement branch.
