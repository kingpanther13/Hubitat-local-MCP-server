# Manual sandbox investigation fixtures

These disposable fixtures are for an explicitly authorized live-hub investigation,
not automatic installation by E2E. Create new Apps Code/Driver Code and uniquely
named scratch instances. Never replace an existing app/driver or select an existing
household device. The driver only emits its own attribute events; it has no physical
device or network effects. Delete owned instances before deleting their code.

- `sandbox-map-probe.groovy`: small typed/untyped read/write/get/put matrix.
- `sandbox-map-candidates.groovy`: assignment/declaration/closure forms for all
  24 issue candidates plus adjacent access controls. Input names and list/default
  cases are available on separate pages. Numeric inventory IDs inside isolated
  methods are synthetic data; those methods do not look up or mutate an app.
- `sandbox-map-attributes.groovy`: inert attribute source for room and snapshot
  checks. Select only a newly created device using this driver.

Keep investigation results and reachability limits in local evidence files.
A fragment failure is not proof of a reachable tool failure.
Snapshots and canonicalization were also compared using exact before/after helper
sources inside an owned app; the fallback snapshot used an adapter forcing an
empty currentStates collection. Raw hub responses and installed code-ID mappings
are private local evidence, not repository fixtures.
