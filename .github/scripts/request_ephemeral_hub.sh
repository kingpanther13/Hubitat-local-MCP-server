#!/usr/bin/env bash
# Requests an ephemeral test hub (docs/ephemeral-hub-e2e.md). Trusted callers only: the base checkout
# in hub-e2e-ephemeral-request.yml, and push/dispatch runs of hub-e2e-ephemeral.yml.
#
#   release SHA              wait until bundle-artifacts serves shas/SHA/ (the provider installs it)
#   consumer HEAD_SHA DIR    find the running pull_request run of hub-e2e-ephemeral.yml for HEAD_SHA,
#                            write run_id/attempt to $GITHUB_OUTPUT and fetch its public key into DIR
#   key RUN_ID ATTEMPT DIR   fetch that run attempt's public key for HUB_KIND into DIR
#   hub KEY_FILE DIR         dispatch the provider and download into DIR its suite results
#                            (RUN_SUITE=true) or the still-encrypted session (RUN_SUITE=false)
#
# Env: GITHUB_REPOSITORY; GH_TOKEN (actions:read, or the provider token for `hub`);
#      HUB_KIND (os|jvm, default os) picks the hub and the test run's key;
#      `hub` also needs PROVIDER, PUBLIC_SHA, PUBLIC_REPO, CONSUMER_RUN_ID, and takes
#      RUN_SUITE (true|false, default true).
set -euo pipefail

fail() { echo "::error::$1"; exit 1; }

kind="${HUB_KIND:-os}"
[[ "$kind" =~ ^(jvm|os)$ ]] || fail "HUB_KIND must be jvm or os."

release() {
  for _ in $(seq 1 24); do
    curl -fsS --max-time 20 -o /dev/null \
      "https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/bundle-artifacts/shas/$1/hubitat-mcp-server.groovy.size" && return 0
    sleep 10
  done
  fail "Release files for $1 were not published within 4 minutes."
}

key() {
  local deadline=$((SECONDS + 600))
  until gh run download "$1" --repo "$GITHUB_REPOSITORY" --name "ephemeral-hub-public-key-$2-$kind" --dir "$3" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "Run $1 published no public key within 10 minutes."
    sleep 10
  done
  # The key comes from a run of PR-controlled code: pass it on only if it is one.
  openssl pkey -pubin -in "$3/session-pub.pem" -noout 2>/dev/null || fail "Run $1 published no valid public key."
}

consumer() {
  local deadline=$((SECONDS + 600)) found=""
  # Only a run still in progress can decrypt: a finished run's private key is gone.
  until [ -n "$found" ]; do
    [ "$SECONDS" -lt "$deadline" ] || fail "No running test run for $1 within 10 minutes."
    sleep 10
    found="$(gh run list --repo "$GITHUB_REPOSITORY" --workflow hub-e2e-ephemeral.yml --event pull_request \
      --commit "$1" --limit 10 --json databaseId,status,attempt \
      --jq '[.[] | select(.status != "completed")] | first | select(.) | "\(.databaseId) \(.attempt)"' || true)"
  done
  read -r run_id attempt <<< "$found"
  [[ "$run_id" =~ ^[0-9]+$ && "$attempt" =~ ^[0-9]+$ ]] || fail "Unexpected test run lookup result."
  printf 'run_id=%s\nattempt=%s\n' "$run_id" "$attempt" >> "$GITHUB_OUTPUT"
  key "$run_id" "$attempt" "$2"
}

# The provider ran the suite next to the hub; its scrubbed results appear once its run completes.
results() {
  local deadline=$((SECONDS + 2400)) polls=0
  export ARTIFACT="ephemeral-hub-results-$1"
  has_results() {
    [ -n "$(gh api "repos/$PROVIDER/actions/runs/$1/artifacts" \
      --jq '.artifacts[] | select(.name == $ENV.ARTIFACT) | .id' || true)" ]
  }
  # Results land at the end of a suite of eight minutes or more: wait five, then read once a
  # minute, checking every fifth read whether the run ended without them.
  sleep 300
  until has_results "$1"; do
    [ "$SECONDS" -lt "$deadline" ] || fail "The hub provider run did not complete within 40 minutes."
    polls=$((polls + 1))
    if [ $((polls % 5)) -eq 0 ] \
        && [ "$(gh run view "$1" --repo "$PROVIDER" --json status --jq .status || true)" = completed ]; then
      has_results "$1" || fail "The hub provider run completed without suite results."
      break
    fi
    sleep 60
  done
  gh run download "$1" --repo "$PROVIDER" --name "$ARTIFACT" --dir "$2"
  local entry name
  shopt -s nullglob dotglob
  for entry in "$2"/*; do
    name="${entry##*/}"
    # Checked before the name is printed: a downloaded name never reaches a log or a command unchecked.
    [[ "$name" =~ ^[A-Za-z0-9._-]+$ && -f "$entry" && ! -L "$entry" ]] \
      || fail "The provider's suite results hold an entry that is not a plain, safely named file."
    case "$name" in
      results.json|e2e.log|sdk.log|harness.log) ;;
      *) fail "The provider's suite results hold an unexpected file: $name" ;;
    esac
  done
  shopt -u nullglob dotglob
  test -s "$2/results.json" || fail "The provider's suite results have no results.json."
  # The provider's own stage log stays private; only the suite's results are relayed.
  rm -f -- "$2/harness.log"
  # The relayed artifact bypasses log masking, so scrub what GitHub masks in this job's log.
  for name in results.json e2e.log sdk.log; do
    [ -f "$2/$name" ] || continue
    perl -pe 's/\Q$ENV{PROVIDER}\E/***/g; s/\Q$ENV{GH_TOKEN}\E/***/g' < "$2/$name" > "$2/$name.tmp"
    mv -- "$2/$name.tmp" "$2/$name"
  done
}

hub() {
  : "${PROVIDER:?}" "${PUBLIC_SHA:?}" "${PUBLIC_REPO:?}" "${CONSUMER_RUN_ID:?}" "${GH_TOKEN:?}"
  local suite="${RUN_SUITE:-true}" since run_id="" deadline=$((SECONDS + 1500))
  [[ "$suite" =~ ^(true|false)$ ]] || fail "RUN_SUITE must be true or false."
  since="$(date -u -d '-30 seconds' +%Y-%m-%dT%H:%M:%SZ)"
  gh workflow run ephemeral-hub.yml --repo "$PROVIDER" \
    -f public_key="$(base64 -w0 "$1")" -f public_sha="$PUBLIC_SHA" -f public_repo="$PUBLIC_REPO" \
    -f consumer_repo="$GITHUB_REPOSITORY" -f consumer_run_id="$CONSUMER_RUN_ID" \
    -f seconds=7200 -f watchdog=false -f kind="$kind" -f run_suite="$suite" >/dev/null
  # The provider's run-name carries "for <consumer repo>#<consumer run id> (<kind>, ...)"; both kinds'
  # runs share the run id, so the kind picks this one.
  export MATCH=" for ${GITHUB_REPOSITORY}#${CONSUMER_RUN_ID} (${kind}," SINCE="$since"
  while [ -z "$run_id" ]; do
    [ "$SECONDS" -lt "$deadline" ] || fail "The hub provider run did not start within 25 minutes."
    sleep 20
    run_id="$(gh run list --repo "$PROVIDER" --workflow ephemeral-hub.yml --limit 50 \
      --json databaseId,displayTitle,createdAt \
      --jq '[.[] | select((.displayTitle | contains($ENV.MATCH)) and .createdAt >= $ENV.SINCE)] | first | .databaseId // empty' || true)"
  done
  echo "Provider run $run_id."
  if [ "$suite" = true ]; then results "$run_id" "$2"; return; fi
  export ARTIFACT="ephemeral-hub-$run_id"
  until [ -n "$(gh api "repos/$PROVIDER/actions/runs/$run_id/artifacts" \
      --jq '.artifacts[] | select(.name == $ENV.ARTIFACT) | .id' || true)" ]; do
    [ "$SECONDS" -lt "$deadline" ] || fail "No hub session within 25 minutes."
    [ "$(gh run view "$run_id" --repo "$PROVIDER" --json status --jq .status || true)" != completed ] \
      || fail "The hub provider run ended without a session."
    sleep 30
  done
  gh run download "$run_id" --repo "$PROVIDER" --name "$ARTIFACT" --dir "$2"
  test -s "$2/session.key.enc" && test -s "$2/session.json.enc" || fail "The hub session is incomplete."
}

case "${1:-}" in
  release) release "$2" ;;
  consumer) consumer "$2" "$3" ;;
  key) key "$2" "$3" "$4" ;;
  hub) hub "$2" "$3" ;;
  *) fail "usage: $0 release|consumer|key|hub ..." ;;
esac
