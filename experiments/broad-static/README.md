# Broad static preparation

This generator runs only in GitHub Actions. It consumes the checkout's current
app/libraries, so catalog and closure changes can precede it without rebasing a
precomputed source patch. It never executes the app DSL or changes state storage.

The previous relaxed scan compiled but failed behavior: static `List + Object`
inserted nested classification lists; static collection methods selected the
wrong Map closure overload; direct sibling calls skipped metaclass interception
used by the existing full suites. Broad preparation treats those as dispatch
contracts, rather than deleting all connected candidates.

New candidate bodies call generated `@CompileDynamic` adapters for declared
siblings and Hubitat methods. These adapters keep dynamic method selection and
test interception. Their return types come from existing explicit signatures or
unambiguous return expressions, including named fields of returned map literals.
Ambiguous returns stay Object. Original externally called signatures stay intact.
Unreassigned Map/List/Set/String locals get explicit types when their initializer
shape is proven. Shared dynamic adapters for each/collect/collectEntries/findAll/
find/any/every/inject preserve runtime collection overload and closure arity.
Remaining compiler errors can create a small dynamic expression helper capturing
lexical values. Expressions that write lexical variables, contain closures,
spread arguments, or depend on closure identity are not extracted.

Methods referencing `state` and `atomicState` remain eligible. Shared dynamic
binding getters keep both original persistence proxies intact; they never cast
them to Map, copy their contents, or cache them. Property/index assignment,
compound assignment, and increment expressions involving either store can move
as a complete original expression into a dynamic helper, keeping evaluation order
and getter/setter semantics. State access remains synchronous in the same script
instance and execution. Existing synchronized statements and lock scope remain
in their original method bodies.

UI DSL delegation, runtime introspection, overloaded declarations, and this
generator's own helper names remain dynamic. Future
closure-method helpers are eligible. No new void contract is
inferred. Every rejected method/error is included in the report. A successful
preparation is not behavior approval: all 11 existing non-E2E lanes on the exact
applied SHA still precede HPM installation.

Remote commands (from a checkout containing these files):

```sh
./gradlew -p experiments/compile-static -I experiments/broad-static/remote.gradle prepareBroad24 -PbroadRoot="$PWD"
./gradlew -I experiments/broad-static/remote.gradle prepareBroad -PbroadRoot="$PWD"
./gradlew -p ci/groovy2x-spock -I "$PWD/experiments/broad-static/remote.gradle" prepareBroad -PbroadRoot="$PWD"
python experiments/broad-static/assemble.py --root "$PWD" --results experiments/broad-static/results --output experiments/broad-static/prepared
```

The assembler intersects per-runtime method plans and requires byte-identical
edits/helpers for each retained method. Apply only its guarded patch (or exact
source tree) before remote parse, measurement, and full suites. Archive all three
reports, manifests, the patch, and baseline/candidate measurements. Do not run any
of these commands locally on the resource-constrained workstation.

`.github/workflows/broad-static-preparation.yml` wires the three compiler
classpaths into parallel preparation jobs, then applies the exact intersection
in a disposable checkout for normal parse/budget checks and actual-source
measurement. The parent then applies the patch, commits it, and runs all 11
ordinary non-E2E workflows; no separate behavioral test phase is added.
Its push trigger is restricted to `experiment/529-broad-static`; manual dispatch
can run against the cumulative experiment branch after the parent integrates
the tooling. It never publishes code or installs anything on a hub.

The typed harness jobs depend on the existing `testClasses` task and use its
complete `test.runtimeClasspath`, including `HubitatAppScript`, the literal-name
`hubitat.helper.*` stubs, HTTPBuilder, Quartz, Chromecast, and each fork's own
Groovy dependency. Stock 2.4 uses the existing `groovy24` configuration. No new
dependency, compiler stub, or custom runtime class is deployed.

Reports include actual class bytes, class count, callsite slots, dispatch
instructions, parent constant-pool entries, parent method count, and maximum
method bytecode. Budget checks match the normal parser: CP < 64,000 and method
code < 60,000 bytes, plus the JVM's 65,535 method-count limit. Per-pass diagnostics
are archived even when discovery cannot converge. The discovery model's metrics
must be distinguished from the assembled source's verified metrics.

Necessary exclusions are explicit in the reports. A setter expression containing
a lexical-variable assignment or a closure cannot be moved without changing
captures/closure ownership, so it remains unresolved rather than being rewritten
as a raw Map write. Delegated UI callbacks keep their original DSL resolution.
Overloads remain dynamic because plans are keyed by method name; return adapters
are narrowed only if all overloads share the same declared return type. A method
that still cannot compile after safe boundaries is reported with every compiler
diagnostic for the next compiler-driven typing pass, not silently counted as
converted. None of these rules excludes a method merely for reading persisted
state or for holding a synchronized lock.
