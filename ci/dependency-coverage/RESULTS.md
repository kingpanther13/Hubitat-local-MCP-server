# Hubitat dependency measurement — 2026-10-12

The updated dependencies pass the existing tests with no missing tests, new skips,
or lost covered application lines. Per-test application coverage is identical
except for four distinct concurrency tests. Exact branch equivalence is therefore
**not established**; the strict comparison remains failing rather than ignoring
those differences. Investigation also found an existing test silently ignoring a
latch timeout under old Spock; the updated dependencies eliminate that timeout.

The PR candidate is still `experiment/dependency-compat-20261012` at
`78115a5a59bc922adfab4ed02a690bbd874ed4bf`: five dependency substitutions in two
build files. Application code, tests, compatibility pins, and bot settings on
that branch remain unchanged. Nothing was merged or changed on main.

## Full comparison

Baseline declarations came from `38115a634b3853c474f093da93c5c9240aa32b1e`.
Both versions ran against the same application and test source on the measurement
branch, with the same instrumentation and test JVM heap limit.

| Configuration | Passed, each version | Skipped, each version | Tests with differing application coverage | Covered lines lost |
|---|---:|---:|---:|---:|
| Groovy 3, gateways | 9,057 | 134 | 2 | 0 |
| Groovy 3, flat | 9,057 | 134 | 3 | 0 |
| Groovy 3, strict, gateways | 9,050 | 141 | 2 | 0 |
| Groovy 3, strict, flat | 9,050 | 141 | 2 | 0 |
| Groovy 2.5, strict | 8,976 | 141 | 4 | 0 |

There were 45,190 passing test executions per dependency set. All passing tests
have individual coverage snapshots. JUnit identities, outcomes and multiplicities
also match the saved uninstrumented runs; no existing tests or skips changed.
All 2,073 application-source class identities match within each lane. Server,
inlined libraries, rule app, and tested watchdog versions are included.

The 308 raw per-iteration probe differences reduce to 13 application-source
differences across configurations, belonging to these four distinct tests:

- `MrtrContinuationSpec`: a rejoined call observing the original finishing.
- `MrtrContinuationSpec`: simultaneous cap-one device calls on two instances.
- `MrtrContinuationSpec`: 24 identical requests coalescing onto one execution.
- `WatchdogV3ManualSpec`: two overlapping health ticks issuing one reboot POST.

The remaining raw differences are in compiler-generated execution machinery:
JaCoCo's application line/method/instruction/branch counters for those test/class
pairs are identical. These were resolved from the actual class dumps and each
test's execution data, not removed with test-name exclusions.

Aggregate branch counts (old → updated) are 33,611 → 33,609 for gateways;
33,613 → 33,611 for flat; 33,601 → 33,597 for strict gateways;
33,599 → 33,599 for strict flat; and 33,566 → 33,562 for Groovy 2.5.
Equal totals are not used to claim equal branch identities. The covered line sets
are identical, while partially covered lines and branch outcomes can differ.

Evidence: [full collection](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/38251046042),
[portable integrity check and source-level analysis](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/38252921958).
The collection jobs report failure because a post-test log scan invoked unavailable
`rg`; all ten Gradle test/report tasks succeeded. The subsequent Python scan
checked the saved build logs and JUnit XML, including captured stderr, for
instrumentation errors. The comparison remains failing because coverage differs.

## Repeatability and the hidden timeout

Two repeated runs per dependency set and Groovy version exercised 1,798 tests
each. All eight test jobs passed. Old-versus-old and updated-versus-updated
comparisons also show concurrency-related branch variation: cache reads/writes,
request coalescing, and rejoin handling depend on thread interleaving.
This prevents treating every branch difference as an upgrade regression.

Evidence: [repeat collections](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/38252758947),
[same-version and cross-version source comparisons](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/38254107145).

One difference warranted a separate diagnostic. In `MrtrContinuationSpec` at
line 3209, `ownerDone.await(5, TimeUnit.SECONDS)` ignores its boolean result.
In the full runs the old test passes after 5.012–5.018 seconds; the updated test
passes after 0.007–0.015 seconds. A diagnostic run adds exactly one assertion to
that wait, on the runner only:

```groovy
assert ownerDone.await(5, TimeUnit.SECONDS)
```

With that assertion, **both old-dependency lanes fail because the wait returned
false; both updated-dependency lanes pass**. Each diagnostic runs 95 tests; the
old lanes fail only the newly asserted wait. This is a real pre-existing hole
in the test, not a newly failing application assertion.

Evidence: [latch assertion diagnostic](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/38254088769).
The result is consistent with Spock's documented removal of synchronization
around calculated mock responses, which fixed waits between mock callbacks.
See [Spock 2.4 release notes](https://spockframework.org/spock/docs/2.4/release_notes.html).
The relevant harness callback delegates `pauseExecution` through a Spock mock.

## Recommendation and limits

The measurement found no application regression attributable to the dependency
updates. The upgrade is a reasonable PR candidate, with the missing latch assertion
worth including so this timeout cannot silently pass again. A literal claim that
every test executes identical branches would be false. Making that a stable gate
would require explicitly controlling concurrency schedules and testing both
interleavings, rather than accepting whichever schedule a run happens to produce.

Coverage measures executed source regions, not exact instruction ordering, call
counts, timing, or untested behavior. This was a JVM unit-test measurement, not
a live-hub E2E execution. Compatibility pins for Groovy, JDK, HubitatCI, and
Chromecast were retained; no Dependabot or Renovate settings were changed.

See [README.md](README.md) for instrumentation details, the constant-pool workaround,
and JaCoCo attribution. Raw coverage and class dumps are attached to the runs;
the comparison artifacts include per-test source differences and repeat results.
