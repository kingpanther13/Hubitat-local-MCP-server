# Vendored official MCP JSON Schemas

These two files are **verbatim copies** of the Model Context Protocol's own published JSON
Schemas. They are the referee for `McpWireSchemaConformanceSpec`, which validates real rendered
wire responses from the dispatch harness against them — so a conformance verdict comes from the
spec authors' schema rather than from this repo's reading of the spec prose.

They are vendored (not fetched) so **the Spock suite needs no network**: CI, the Groovy 2.5
lane, and a local `./gradlew test` all validate against these bytes.

## What's here

| Path | Upstream path | Dialect | Covers |
|---|---|---|---|
| `2025-06-18/schema.json` | `schema/2025-06-18/schema.json` | draft-07 | The legacy era — `initialize`, `ping`/`EmptyResult`, legacy `tools/list` + `tools/call`, the JSON-RPC envelopes |
| `2026-07-28/schema.json` | `schema/2026-07-28/schema.json` | 2020-12 | The final 2026-07-28 revision — `server/discover`/`DiscoverResult`, `resultType`, the `ttlMs`/`cacheScope` cache hints, and the `-32020` / `-32022` error envelopes |

The directory names mirror upstream's published revision directories. The modern schema is pinned
to the immutable `2026-07-28` tag rather than following the moving `schema/draft/` document.

## Provenance

Source repository: <https://github.com/modelcontextprotocol/modelcontextprotocol>

### `2025-06-18/schema.json`

- URL: <https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2025-06-18/schema.json>
- Last upstream commit touching the file: `35ccd9fd63501a68b733fe3ad9e187f62c7ac839` (2025-09-24, *"Clarify purpose of `maxTokens` param for sampling (#1538)"*)
- Retrieved: 2026-07-26
- 108236 bytes, `sha256 b3db8f1ca839bc5171ceb4ba013fdf240c5a8a13d4653bb1bdf21f94677aa220`
- Declares `"$schema": "http://json-schema.org/draft-07/schema#"`; internal refs are `#/definitions/…`

### `2026-07-28/schema.json`

- URL: <https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/2026-07-28/schema/2026-07-28/schema.json>
- Final upstream tag commit: `5f5440bb26a62e2cf3440b92da5a667efa03b267` (2026-07-28, *"Merge pull request #3158 from modelcontextprotocol/claude/subscriptions-listen-envelope"*)
- Retrieved: 2026-08-11
- 185437 bytes, `sha256 6995b6ed8d0c9ac6e24a186b2de718ef6801de46393ecd911663942c9a36fcd6`
- Declares `"$schema": "https://json-schema.org/draft/2020-12/schema"`; internal refs are `#/$defs/…`

`.gitattributes` marks this directory `-text`, so the bytes above survive a checkout on any
platform (`core.autocrlf=true` would otherwise rewrite the newlines and invalidate the hashes).

**The byte counts and hashes above are ENFORCED, not documentation.** `python tests/sandbox_lint.py`
(rule `MCP_SCHEMA_PROVENANCE`, run by the `sandbox-lint` CI workflow) re-hashes both files and fails
on any drift in either direction — an edited schema, a refreshed file whose provenance wasn't
re-recorded, or a re-recorded hash with no matching file. It also fails on a schema vendored here
with no provenance section at all. So a loosened or half-refreshed schema can't quietly weaken every
`McpWireSchemaConformanceSpec` verdict.

## Refreshing

Published revision schemas are immutable; add or replace a versioned file only when adopting a
new MCP revision.
From the repo root:

```bash
curl -sSfo src/test/resources/mcp-schema/2025-06-18/schema.json \
  https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2025-06-18/schema.json
curl -sSfo src/test/resources/mcp-schema/2026-07-28/schema.json \
  https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/2026-07-28/schema/2026-07-28/schema.json
```

Then re-record provenance in this file — the commit SHA, date, byte count, and hash — and re-run
both the lint (which fails until the recorded hash matches the new bytes) and the suite:

```bash
# commit SHA + date the file was last changed upstream
gh api "repos/modelcontextprotocol/modelcontextprotocol/commits?path=schema/2026-07-28/schema.json&sha=2026-07-28&per_page=1" \
  --jq '.[0] | {sha, date: .commit.committer.date, msg: (.commit.message | split("\n")[0])}'
# byte count + hash
python -c "import hashlib,sys; b=open(sys.argv[1],'rb').read(); print(len(b), hashlib.sha256(b).hexdigest())" \
  src/test/resources/mcp-schema/2026-07-28/schema.json

python tests/sandbox_lint.py                                       # enforces the recorded hashes
./gradlew test --tests "server.McpWireSchemaConformanceSpec"
```

A refresh that turns the spec red is the harness doing its job: either the revision changed a
shape this server emits, or a field this server relies on moved. Fix the server, don't loosen the
schema — these files are never edited by hand.

## How the specs use them

The MCP schemas are single documents keyed by type name, with **no root schema**: the legacy file
has only `$schema` + `definitions`, the modern file only `$schema` + `$defs`. `McpSchemaValidator`
therefore splices a tiny wrapper document whose root is a `$ref` at the requested type and whose
body is the vendored definitions map verbatim:

```json
{ "$schema": "…", "$ref": "#/definitions/InitializeResult", "definitions": { … } }
```

Every reference stays an internal JSON pointer, so nothing is resolved over the network. The
validator also builds a **strict** variant that sets `additionalProperties: false` on one named
definition — used to reproduce the MCP TypeScript SDK's `EmptyResultSchema` (`ResultSchema.strict()`),
which rejects unknown keys and is what made a stray `resultType` on a legacy `ping` a client-side
protocol error. Plain draft-07 can't express that on its own: the published `Result` carries
`additionalProperties: {}` and accepts anything.
