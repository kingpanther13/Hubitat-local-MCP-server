#!/usr/bin/env bash
# Deliver every library the PR's app #includes, BEFORE the app is deployed, so the `#include`
# directives resolve when the app compiles on the hub (issue #209).
#
# Design: deliver libraries the SAME way HPM/users do -- via the bundle. `hub_install_bundle`
# (present since #243) creates each library fresh AND updates an existing bundle-managed copy IN
# PLACE, in one atomic op. That is the robust path: it can't be tripped by a bundle-managed copy and
# it can't misclassify a cloud-relay timeout as "can't reconcile" and silently strand a library --
# the failure mode that previously left McpRoomsLib deleted and the deploy failing "library not
# found". (Verified on a real hub: installing the bundle over an existing one updates same-(ns,name)
# libraries in place with no duplicate, and updates a bundle-managed library cleanly.) Per-library
# create is kept only as a FALLBACK for anything the bundle didn't deliver (e.g. a deployed app that
# predates hub_install_bundle); a missing library is a plain create, so no edit/delete dance.
# Idempotent. No `#include` -> no-op.
#
# Runs in the GHA runner (the repo is checked out): it reads the LOCAL app + library files to
# discover what to install, matches each #include to its library file by the (namespace, name) in
# its library() declaration (NOT by filename, mirroring the hub), and builds URLs from the PR head
# SHA so the hub fetches the source itself.
#
# Usage: mcp_install_libraries.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL               -- full cloud OAuth URL with access_token
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA

set -uo pipefail

: "${MCP_URL:?MCP_URL env var required}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required}"

APP_FILE="${1:-hubitat-mcp-server.groovy}"
if [ ! -f "$APP_FILE" ]; then
  echo "::error::App file not found: $APP_FILE (wrong working directory or bad path arg). Refusing to silently report 'no #include directives'."
  exit 1
fi
LIB_DIR="$(dirname "$APP_FILE")/libraries"
BUNDLE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/bundles/mcp-libraries.zip"

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" --data-binary "$1"
}

# Echo the inner MCP tool-result text for an RPC body. `|| true` so a transient relay
# failure doesn't bare-exit before the caller inspects the (empty) result.
call_tool() {
  local resp
  resp=$(mcp_call "$1" || true)
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

# Echo "true"/"false" for a tool result's .success field.
ok_of() { printf '%s' "$1" | jq -r '.success // false' 2>/dev/null || echo false; }

# Echo the current hub library list as a compact JSON array (the tool's `.libraries`), or "[]".
list_libraries_json() {
  local text
  text=$(call_tool '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  printf '%s' "$text" | jq -c '.libraries // []' 2>/dev/null || printf '[]'
}

# Return 0 if (ns,name) is present in a pre-fetched library list JSON array.
lib_present_in() {
  local libs_json="$1" ns="$2" name="$3" id
  id=$(printf '%s' "$libs_json" | jq -r --arg ns "$ns" --arg name "$name" \
    'map(select(.namespace==$ns and .name==$name)) | (.[0].id // empty)' 2>/dev/null || true)
  [ -n "$id" ]
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

# Library/bundle writes are destructive-gated (confirm + <24h backup). Refresh the timestamp
# best-effort; the gate is timestamp-cached so a 504 here is tolerated (CI keeps the window warm).
echo "Triggering hub backup (best-effort; the <24h gate is timestamp-cached)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup call failed/timed out; relying on cached <24h backup timestamp"

# --- PRIMARY: deliver ALL #include'd libraries by installing the bundle.
echo "Installing the libraries bundle to deliver the app's #include'd libraries: ${BUNDLE_URL}"
BUN_RPC=$(jq -nc --arg url "$BUNDLE_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
BUN_TEXT=$(call_tool "$BUN_RPC")
if [ "$(ok_of "$BUN_TEXT")" = "true" ]; then
  echo "Bundle installed: $(printf '%s' "$BUN_TEXT" | jq -c '{success,endpoint}' 2>/dev/null || true)"
else
  echo "::warning::hub_install_bundle did not report success (the deployed app may predate it, or a transient relay error): $(printf '%s' "$BUN_TEXT" | jq -c '{success,error}' 2>/dev/null || printf '%s' "$BUN_TEXT" | head -c 200). Falling back to per-library create for any missing library."
fi

# Verify each #include is now present; collect anything the bundle didn't deliver.
LIBS_JSON=$(list_libraries_json)
MISSING=()
for tok in "${INCLUDES[@]}"; do
  ns="${tok%%.*}"; name="${tok##*.}"
  if lib_present_in "$LIBS_JSON" "$ns" "$name"; then
    echo "Present after bundle install: ${tok}"
  else
    echo "::warning::${tok} not present after bundle install -- will create it individually."
    MISSING+=("$tok")
  fi
done

if [ "${#MISSING[@]}" -eq 0 ]; then
  echo "All ${#INCLUDES[@]} PR library(ies) delivered by the bundle -- the app's #include directives will resolve on deploy."
  exit 0
fi

# --- FALLBACK: create any library the bundle did not deliver. These are MISSING (not bundle-managed),
# so a plain create is safe -- no edit/delete of a bundle-managed copy, hence no timeout-misclassify.
echo "Creating ${#MISSING[@]} library(ies) the bundle did not deliver: ${MISSING[*]}"
for tok in "${MISSING[@]}"; do
  ns="${tok%%.*}"; name="${tok##*.}"
  libfile=""
  for f in "$LIB_DIR"/*.groovy; do
    [ -f "$f" ] || continue
    if grep -qE 'library[[:space:]]*\(' "$f" \
      && grep -qE "name:[[:space:]]*[\"']${name}[\"']" "$f" \
      && grep -qE "namespace:[[:space:]]*[\"']${ns}[\"']" "$f"; then
      libfile="$f"; break
    fi
  done
  if [ -z "$libfile" ]; then
    echo "::error::#include ${tok} has no matching library file in ${LIB_DIR}"
    exit 1
  fi
  url="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${libfile#./}"
  echo "Creating library ${tok} from ${url} ..."
  CRT_RPC=$(jq -nc --arg url "$url" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_library",arguments:{importUrl:$url,confirm:true}}}')
  CRT_TEXT=$(call_tool "$CRT_RPC")
  if [ "$(ok_of "$CRT_TEXT")" = "true" ]; then
    echo "Library ${tok} created."
    continue
  fi
  # A create that times out on the relay may have landed on the hub -- re-verify before failing,
  # rather than trusting the (possibly lost) response.
  if lib_present_in "$(list_libraries_json)" "$ns" "$name"; then
    echo "Library ${tok} present on re-check (create response was lost to a timeout)."
  else
    echo "::error::Library ${tok} create failed: $(printf '%s' "$CRT_TEXT" | jq -c '{success,error,note}' 2>/dev/null || printf '%s' "$CRT_TEXT" | head -c 300)"
    exit 1
  fi
done

echo "All ${#INCLUDES[@]} PR library(ies) present -- the app's #include directives will resolve on deploy."
