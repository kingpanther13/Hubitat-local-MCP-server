#!/usr/bin/env bash
# Install/update every library the PR's app #includes, BEFORE the app is deployed, so the
# `#include` directives resolve when the app compiles on the hub (issue #209).
#
# Design (per maintainer): load whatever libraries the CURRENT PR carries, every run, without
# ever stranding the hub or depending on a tool that might not be present. It uses ONLY the
# always-available baseline code tools -- hub_list_libraries + hub_create_library /
# hub_update_library -- so it can never chicken-and-egg on hub_update_package, and the crucial
# code/package-loading tools are never required to pre-exist. Idempotent: a library already on
# the hub is updated to the PR's source; a missing one is created. No `#include` -> no-op.
#
# Runs in the GHA runner (the repo is checked out): it reads the LOCAL app + library files to
# discover what to install, matches each #include to its library file by the (namespace, name)
# in its library() declaration (NOT by filename, mirroring the hub), and builds each library's
# importUrl from the PR head SHA so the hub fetches the source itself.
#
# Usage: mcp_install_libraries.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL               -- full cloud OAuth URL with access_token
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required}"

APP_FILE="${1:-hubitat-mcp-server.groovy}"
LIB_DIR="$(dirname "$APP_FILE")/libraries"

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" --data-binary "$1"
}

# Parse `#include namespace.Name` directives (one per line) from the app source.
mapfile -t INCLUDES < <(
  grep -hoE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' "$APP_FILE" \
    | sed -E 's/^[[:space:]]*#include[[:space:]]+//' | sort -u || true
)

if [ "${#INCLUDES[@]}" -eq 0 ]; then
  echo "No #include directives in $APP_FILE -- no libraries to install."
  exit 0
fi
echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]}"

# Library writes are destructive-gated (confirm + <24h backup). Refresh the timestamp best-effort;
# the gate is timestamp-cached so a 504 here is tolerated (CI keeps the window warm).
echo "Triggering hub backup (best-effort; the <24h gate is timestamp-cached)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup call failed/timed out; relying on cached <24h backup timestamp"

# Snapshot existing hub libraries once, to choose update-vs-create per library.
LIST_TEXT=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}' \
  | jq -r '.result.content[0].text // empty')
if [ -z "$LIST_TEXT" ]; then
  echo "::error::hub_list_libraries returned no MCP content -- cannot plan create-vs-update"
  exit 1
fi

for tok in "${INCLUDES[@]}"; do
  ns="${tok%%.*}"
  name="${tok##*.}"

  # Find the local library file whose library() declares this (namespace, name).
  libfile=""
  for f in "$LIB_DIR"/*.groovy; do
    [ -f "$f" ] || continue
    if grep -qE 'library[[:space:]]*\(' "$f" \
      && grep -qE "name:[[:space:]]*[\"']${name}[\"']" "$f" \
      && grep -qE "namespace:[[:space:]]*[\"']${ns}[\"']" "$f"; then
      libfile="$f"
      break
    fi
  done
  if [ -z "$libfile" ]; then
    echo "::error::#include ${tok} has no matching library file in ${LIB_DIR}"
    exit 1
  fi
  rel="${libfile#./}"
  url="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${rel}"

  existing_id=$(echo "$LIST_TEXT" | jq -r --arg ns "$ns" --arg name "$name" \
    '(.libraries // []) | map(select(.namespace==$ns and .name==$name)) | (.[0].id // empty)')

  if [ -n "$existing_id" ] && [ "$existing_id" != "null" ]; then
    echo "Updating existing library ${tok} (id ${existing_id}) from ${url} ..."
    RPC=$(jq -nc --arg id "$existing_id" --arg url "$url" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_library",arguments:{libraryId:$id,importUrl:$url,confirm:true}}}')
  else
    echo "Creating library ${tok} from ${url} ..."
    RPC=$(jq -nc --arg url "$url" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_library",arguments:{importUrl:$url,confirm:true}}}')
  fi

  TEXT=$(mcp_call "$RPC" | jq -r '.result.content[0].text // empty')
  OK=$(echo "$TEXT" | jq -r '.success // false')
  if [ "$OK" != "true" ]; then
    echo "::error::Library ${tok} install/update failed: $(echo "$TEXT" | jq -c '{success,error,note,verified}' 2>/dev/null || echo "$TEXT" | head -c 400)"
    exit 1
  fi
  echo "Library ${tok} OK."
done

echo "All ${#INCLUDES[@]} PR library(ies) installed/updated -- the app's #include directives will resolve on deploy."
