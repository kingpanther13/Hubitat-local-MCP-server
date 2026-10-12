# Dependency updates

Renovate proposes Gradle library and wrapper updates. Dependabot continues to
update GitHub Actions; GitHub dependency alerts remain independent of that split.
Every update requires review: Renovate automerge and platform automerge are off.
The weekly window is Monday 08:00–11:59 America/New_York, with at most three
Renovate PRs open at once. Its Dependency Dashboard exposes pending updates and
lookup failures.

## Coverage and compatibility

| Dependency | Source being watched | Constraint |
| --- | --- | --- |
| Root Groovy | Maven Central | 3.0.x |
| Compatibility-build Groovy | Maven Central | 2.5.x |
| Parser Groovy | Maven Central | 2.4.x |
| Root Spock | Maven Central | Stable 2.x, `-groovy-3.0` |
| Compatibility-build Spock | Maven Central | Stable 2.x, `-groovy-2.5` |
| JSON Schema Validator, both builds | Maven Central | 2.x; the test JVM stays Java 11 |
| Chromecast API, both builds | Maven Central | Retain 0.11.1; later POM ranges need deliberate compatibility review |
| Other declared Gradle libraries and wrapper | Their configured repositories / Gradle releases | Review proposed updates through the existing CI lanes |
| `eighty20results/hubitat_ci` | GitHub releases | Update the exact JitPack release tag |
| `joelwetzel/hubitat_ci` | Git `master` head | Update the exact JitPack commit SHA; never a moving snapshot dependency |
| Renovate validation tool | npm | Update the version pinned in the validation workflow |

Configuration lives in [`.github/renovate.json`](../.github/renovate.json). It
discovers the root build and the independent `ci/groovy2x-spock` and
`ci/groovy24-parse` builds. The fork coordinates are disabled only in the ordinary
Gradle manager; separate regex managers perform the GitHub release and Git-ref
lookups. This avoids treating commit hashes as ordered Maven versions or proposing
two updates for the same coordinate.

The existing `hubitat-ci-version-check.yml` remains an independent weekly release
notification for eighty20results. It does not track joelwetzel. That fork is now
covered by Renovate's Git-ref manager. The original `biocomp/hubitat_ci` repository
is ancestry, not a consumed dependency; updates are followed on the two forks
actually loaded by the tests. Our harness still relies on their sandbox, script
base classes, and Hubitat API types, even where it supplies custom adapters.

## Why this extends the previous setup

Dependabot already updated root Gradle libraries and the wrapper (for example,
PRs #396 and #488), as well as Gradle's GitHub Actions. Those working update paths
are preserved through the manager split above. Previously Spock was explicitly
ignored, and the two independent CI builds were not included in the root Gradle
project or configured as separate Dependabot directories.

Dependabot's Gradle updater discovers versions through Maven metadata and directory
listings. It does not map the JitPack `a62a351eed` coordinate to the next commit on
joelwetzel's `master`. Its current suffix matching also parses dot-separated version
segments rather than enforcing the entire Spock Groovy suffix. Adding more
Dependabot directories would improve coverage, but would not supply Git-ref
tracking for this commit pin. These findings were checked against
[Dependabot source at 4725d253](https://github.com/dependabot/dependabot-core/tree/4725d253a379379dc85d291268d9607977b3c1af/gradle/lib/dependabot/gradle)
and its [shared version filters](https://github.com/dependabot/dependabot-core/blob/4725d253a379379dc85d291268d9607977b3c1af/maven/lib/dependabot/maven/shared/shared_version_finder.rb).

Renovate supports [version restrictions](https://docs.renovatebot.com/configuration-options/#allowedversions)
and [Git-ref digest updates](https://docs.renovatebot.com/modules/datasource/git-refs/)
for these cases. Monitoring upstream does not commit us to accepting an update.
An upstream change may require adapting the harness, or retaining the current pin.

## Activation

Enable the [Renovate GitHub App](https://github.com/apps/renovate) for
`kingpanther13/Hubitat-local-MCP-server` using **Only select repositories**.
The configuration takes effect after it reaches the default branch and the app
has access. A config file by itself does not install or activate the hosted bot.
The maintainer controls that installation and merging; neither is performed by
the validation workflow.

## Hub E2E

Renovate and Dependabot PRs skip the deployment approval and live-hub jobs only
when every changed file is in the dependency/CI allowlist in
`.github/scripts/lane_gate.js`. The early gate posts an explicit “E2E not required”
status; it does not claim the hub tests passed. Other CI checks still run. Workflow changes qualify only for complete action/version
pin replacements; changed commands or added/replaced actions retain normal E2E.

Changes to shipped app/library sources, package metadata, or app-generation tools
retain the normal E2E flow, even on a bot-authored PR. Unknown paths, renames from
non-exempt paths, API failures, incomplete file lists, and a head that changed
during classification never grant the exemption. The PR author determines bot
identity, so a maintainer clicking re-run does not create an approval request for
a dependency-only update. An explicit `workflow_dispatch` can still run E2E;
`e2e:skip` continues to hold the merge gate pending.

## Validation

`dependency-updates-check.yml` runs the pinned official configuration validator,
then Renovate's real extraction and live lookup against disposable copies of
the build files using its local dry-run platform and a read-only token. It checks
that all three Groovy lanes, both Spock variants, both forks, the wrapper, and the
validator itself are discovered. A second copy uses older Spock/schema versions,
an older eighty20results release, and joelwetzel's preceding commit to prove that
each kind of update is actually proposed. Compatibility violations, missing
dependencies, lookup warnings, and missing expected updates fail the check.

The dry run never opens PRs, changes pins in the checkout, or executes Gradle.
It validates discovery and selection, not whether a future upstream release
compiles or behaves correctly. Actual update PRs must pass the applicable test
lanes before a maintainer merges them.
