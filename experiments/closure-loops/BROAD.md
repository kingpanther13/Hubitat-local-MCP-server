# Broad closure candidate

Prepared in the isolated dbf2448d worktree; no local parsing, compilation, builds,
tests, generation, commits, pushes, CI dispatch, or hub writes were performed.

The generator targets 35 synchronous collection/string/numeric callback names,
including each, collect, collectEntries, collectMany, find/findAll, any/every,
eachWithIndex, inject, sort/unique, min/max/sum, count/groupBy, and replacements.
Read-only text search finds 1,414 collection-closure-looking calls across the
parent and libraries. This is the candidate surface, not an eligibility result.
The expected scale is hundreds of generated closure classes, with no small cap;
the remote manifest reports the exact count and all exclusion reasons.

Each eligible literal callback becomes a public named method in its existing
source file. A MethodClosure invokes it, with a CurriedClosure when it captures
read-only locals. Those are stock Groovy runtime classes: the generator adds no
anonymous callback closures or replacement generated closure classes.
Captured object references remain the same objects; mutable local bindings stay
inline rather than being snapshotted incorrectly. No state/atomicState/static
cache relocation, new persisted state, or monitor changes occur.

The original receiver and operation remain at their original evaluation position.
There is no receiver allowlist: variable/property receivers, decoder calls, casts,
arbitrary expressions, expression contexts, nested and chained calls are supported.
Custom receiver method dispatch stays on the original receiver. Arguments are
rebuilt in their original order using ordinary parenthesized call syntax, covering
inject(initial) { ... }, explicit arguments, and multiple direct callbacks.
Map entry/key-value arity, closure parameter types, safe navigation, collection
return values, sort comparator/key mode, ordering, mutation/fail-fast behavior and
closure-local early returns stay with the existing Groovy operation.

AST lexical binding identifies captures, including a captured local invoked as
fix(value). Writes anywhere in the enclosing method, including nested closures,
exclude a captured binding. Object mutation through an unchanged reference is
permitted. Parameter names/types and effective callback arity are retained after
currying; an Object[] contains captures so captured arrays are not accidentally
spread into the curry argument list.

Necessary exclusions: DSL/root blocks, DSL ancestors/body calls, unsupported
foreign callback binding, closure identity/delegate/inherited self-method use,
custom category/metaclass files, mutable capture bindings or lexical name
collisions, annotated/default/array-varargs callback parameters, anonymous class
binding, implicit/spread/super calls, and callbacks in existing CompileStatic
methods. The latter preserves the previous static runtime optimization and
generic type inference; moving those bodies into dynamic helpers would undo it.
Scheduling/HTTP callbacks, stored recursive closures and DSL blocks themselves
are untouched; suitable collection callbacks nested inside them can be lowered
only when their property/method binding is explicit and safe.

Existing documentation permits groovy.lang.Closure but does not specifically
prove Hubitat acceptance of MethodClosure/curry. No direct imports or constructor
calls of those implementation classes are emitted. Ordinary remote Groovy 2.4
parse, full unit/compatibility CI, sandbox lint and actual HPM app save are the
required evidence. No extra parity or live probes are added.

Generated helpers retain original body size rather than making one giant switch.
The manifest reports largest helper source length; source length is not bytecode
evidence. Existing parse24/parseClone class-generation checks enforce the actual
64,000 constant-pool and 60,000 method-bytecode budgets before HPM installation.

## Parent handoff

Copy these four tooling/workflow changes onto the current retained cumulative
candidate after existing task CI finishes:

* experiments/closure-loops/broad.groovy
* experiments/closure-loops/apply_broad.py
* experiments/closure-loops/remote.gradle (new bat529BroadClosurePrepare task)
* .github/workflows/compile-static-experiment.yml (new broad-closures mode)

The existing converter is used only for proven source-span/brace pairing utilities.
There is no legacy converter regeneration, fixture dependency, or candidate source
application in this worktree.

Parent dispatches mode broad-closures, publish=false, on the retained candidate.
It runs stock Groovy 2.4.21/JDK11 and uploads results/broad/manifest.json plus exact
rewritten parent/library files. Current catalog decoder calls and casts require no
special treatment because collection receivers are preserved verbatim.

After download, parent applies with:

    python experiments/closure-loops/apply_broad.py REPO RESULTS_BROAD --check-only
    python experiments/closure-loops/apply_broad.py REPO RESULTS_BROAD

Application validates every input/output SHA and library inventory before any
write. Nested call edits are reconstructed in UTF-16; no modifications outside
recorded call spans and appended helper definitions are permitted.

Parent then runs all eleven ordinary non-E2E workflows plus experiment verify for
the exact applied SHA. No changes to tests or lint are included. Full CI may
identify source-structural expectations or bounded-map subscripts in moved helper
bodies; resolve those concrete failures without weakening the guards.

HPM timing follows the owner's cumulative rules: >=60 seconds improvement pauses
following experiments; >=30 seconds slower requires a version-bump retry and revert
if still >=30 seconds slower; otherwise retain cumulatively. Parent owns version,
publication, installation, retention decisions, and source/artifact readback.
