#!/usr/bin/env bash
# Publish a FORK PR's library bundle to THIS repo's bundle-artifacts branch (shas/<head-sha>/ only).
#
# publish-bundle-artifact.yml only runs on pushes to this repo, so a fork PR never gets the
# shas/<sha>/ entry the e2e install (mcp_watchdog_deploy.sh) fetches. hub-e2e.yml's fork-bundle job
# runs this once the `approve` gate clears; the e2e job then points PR_RAW_BASE at this repo
# (raw.githubusercontent.com serves any fork-network commit from the base repo).
#
# Trust model: the working tree is the BASE checkout, so tools/build-bundle.py and this script are
# main's. Only the PR's libraries/ is copied in, as data -- regular files only, so a symlink can't
# pull a runner file into a publicly served zip. Never writes branches/<name>/: a fork branch named
# `main` would otherwise overwrite the bundle HPM users install.
#
# The control doing the most work is in the BUILDER, not here: tools/build-bundle.py has its twenty
# library paths HARDCODED and only read_text -> CRLF-normalize -> deflate, with a verify() asserting
# the entry set exactly. That is why a fork adding libraries/anything-else.groovy is ignored, and why
# contents: write is safe in a job that reads fork content at all. The builder must never DISCOVER
# its own inputs -- a glob over libraries/*.groovy would turn this into a code-delivery surface with
# repo write, in a diff that reads like a cleanup (tests/test_build_bundle_inputs.py guards it).
#
# Env: GH_TOKEN (contents:write), BASE_REPO (owner/name), EVENT_NAME, and either
#      PR_HEAD_REPO + PR_HEAD_SHA (pull_request_target) or PR_INPUT (workflow_dispatch pr_number).
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN required}"
: "${BASE_REPO:?BASE_REPO required}"

if [ "${EVENT_NAME:-}" = "pull_request_target" ]; then
  REPO="${PR_HEAD_REPO:-}"; SHA="${PR_HEAD_SHA:-}"
else
  if ! printf '%s' "${PR_INPUT:-}" | grep -qE '^[0-9]+$'; then
    echo "::error::pr_number must be numeric"; exit 1
  fi
  REPO=$(gh api "repos/$BASE_REPO/pulls/$PR_INPUT" --jq '.head.repo.full_name')
  SHA=$(gh api "repos/$BASE_REPO/pulls/$PR_INPUT" --jq '.head.sha')
fi
if ! printf '%s' "$SHA" | grep -qE '^[0-9a-f]{40}$'; then
  echo "::error::unexpected head SHA shape: $SHA"; exit 1
fi
if ! printf '%s' "$REPO" | grep -qE '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'; then
  echo "::error::unexpected head repo shape: $REPO"; exit 1
fi
if [ "$REPO" = "$BASE_REPO" ]; then
  echo "Same-repo PR -- publish-bundle-artifact.yml already published shas/${SHA}/ on push. Nothing to do."
  exit 0
fi

git fetch --no-tags --depth=1 origin "$SHA"

BAD=$(git ls-tree -r "$SHA" -- libraries/ | awk '$1 != "100644" && $1 != "100755" { print $4 }')
if [ -n "$BAD" ]; then
  echo "::error::PR libraries/ contains non-regular files (symlink/submodule) -- refusing to bundle: $BAD"
  exit 1
fi

rm -rf libraries
git checkout "$SHA" -- libraries/
python tools/build-bundle.py

ZIP=bundles/mcp-libraries.zip
BASENAME=$(basename "$ZIP")
BYTES=$(wc -c < "$ZIP" | tr -d '[:space:]')
KEY="shas/${SHA}"

git fetch --no-tags origin bundle-artifacts
git worktree add ../artifacts origin/bundle-artifacts
mkdir -p "../artifacts/${KEY}"
cp "$ZIP" "../artifacts/${KEY}/${BASENAME}"
printf '%s' "$BYTES" > "../artifacts/${KEY}/${BASENAME}.size"

cd ../artifacts
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
if git diff --cached --quiet; then
  echo "Artifact already current for fork PR ${REPO} @ ${SHA}."
else
  git commit -qm "bundle artifact: fork ${REPO} @ ${SHA}"
  PUSH_URL="https://x-access-token:${GH_TOKEN}@github.com/${BASE_REPO}.git"
  pushed=false
  # No shared concurrency group with publish-bundle-artifact.yml (a queue:single group would let one
  # pending publish evict the other); the paths never overlap, so a rebase retry resolves any race.
  for attempt in 1 2 3 4 5; do
    if git push -q "$PUSH_URL" HEAD:bundle-artifacts; then
      pushed=true; break
    fi
    if ! git pull -q --rebase "$PUSH_URL" bundle-artifacts; then
      echo "::warning::rebase onto bundle-artifacts failed on attempt $attempt -- aborting and retrying."
      git rebase --abort 2>/dev/null || true
    fi
    sleep 3
  done
  if [ "$pushed" != "true" ]; then
    echo "::error::Could not push the fork bundle artifact after 5 attempts."; exit 1
  fi
fi
cd - >/dev/null

# The install fetches through raw.githubusercontent.com; wait until it serves these exact bytes so
# the e2e job can't race a fresh push.
URL="https://raw.githubusercontent.com/${BASE_REPO}/bundle-artifacts/${KEY}/${BASENAME}"
TMP=$(mktemp)
for attempt in $(seq 1 36); do
  if curl -fsSL "$URL" -o "$TMP" 2>/dev/null && cmp -s "$ZIP" "$TMP"; then
    echo "Published and serving: $URL (${BYTES} bytes)"
    exit 0
  fi
  sleep 5
done
echo "::error::$URL did not serve the published bytes within 3 minutes."
exit 1
