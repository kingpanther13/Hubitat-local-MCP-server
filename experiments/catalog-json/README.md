# Catalog JSON source generation

Run exclusively in GitHub Actions:

```sh
./gradlew -p experiments/catalog-json prepareCatalog24 --console=plain --no-daemon
```

Output defaults to `experiments/catalog-json/results/groovy24`. Override with `-PcatalogOutput=<absent-or-empty-directory>` if the workflow needs another artifact location. Preparation only parses source to the Groovy 2.4 CONVERSION AST and rewrites literal-safe catalog expressions. It never generates classes, runs app/library DSL, benchmarks, or performs a separate behavior/parity gate.

Artifacts contain `rewritten/`, `replacement-mapping.json`, `selected.json`, `skipped.json`, and `summary.json` (`status: generated`). Every changed file has original/output UTF-8 SHA-256, byte counts, and exact expression replacements. Generation preserves unrelated source, including existing static annotations and loops. Run it on the current cumulative source after applying the closure changes. Before applying any artifact files, verify **all** current worktree hashes equal `originalSha256`; a mismatch requires fresh generation, not an overwrite. Verify artifact bytes against `rewrittenSha256`. Apply only the changed files under `rewritten/`.

The encoding uses tagged JSON arrays and ordered Map key/value pairs. Runtime helpers build fresh LinkedHashMap/ArrayList containers, disable date coercion, and join bounded strings at runtime. Only constant String/Boolean/null/Integer values, literal Lists, and literal Maps with String keys are accepted. No cache, state, atomicState, or storage/concurrency boundary changes are introduced.

The metadata extractor in `tests/embedded_catalog_source.py` reads the actual generated constants for existing lint guards. Raw sandbox scans still inspect actual Groovy source. No separate parity fixtures are added. The resulting candidate must pass the existing complete non-E2E CI before HPM timing; any additional parity work is deferred until a speed improvement is established.
