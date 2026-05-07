<!-- Prefix your PR title with the type that matches what you tick below.
     Valid prefixes: feat: | fix: | chore: | refactor: | docs: | test: | ci:
     Example: "feat: add device health polling tool"
     Note: build: is reserved for Dependabot — contributors should not use it. -->

## Summary

<!-- 1-3 bullets describing what this PR does and why. -->

## Type of change

<!-- Tick exactly one. -->

- [ ] `feat` — new feature or capability
- [ ] `fix` — bug fix
- [ ] `chore` — maintenance, dependency bump, or housekeeping
- [ ] `refactor` — code restructure with no behaviour change
- [ ] `docs` — documentation only
- [ ] `test` — tests only
- [ ] `ci` — CI/CD pipeline change

## Changes

<!-- Key changes. Reference issue numbers (Closes #N / Fixes #N / Part of #N). -->

## Release Notes

<!-- These bullets are what HPM users will see when prompted to update — write for end users, not developers.
     Sub-bullets are supported (nest with 2-space indent).
     If you skip this section, the release entry will fall back to just the PR title. -->

- 

## Testing

<!-- How you verified this works. -->

## Checklist

- [ ] **Unit tests added for any new MCP tools** (required — see [docs/testing.md](docs/testing.md) for the harness + recipes)
- [ ] Sandbox lint passes: `python tests/sandbox_lint.py`
- [ ] `./gradlew test` passes locally (or CI confirms)
- [ ] Live-hub BAT tests updated if tool behaviour changed (see `tests/BAT-v2.md`)
- [ ] Documentation updated if user-facing behaviour or tool surface changed
