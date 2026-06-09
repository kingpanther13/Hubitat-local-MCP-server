#!/usr/bin/env bash
# One-off extraction of Rule-Machine / classic-dynamicPage wire-format evidence
# from the vendored Hubitat admin-UI bundles in resources/hub2-source/.
#
# Runs ONLY on a GitHub Actions runner (never on the Termux dev device, which
# OOM-crashes on the multi-MB minified bundles). Emits small, bounded evidence
# files to /tmp/evidence so the analysis layer never has to touch the raw blobs.
set -u

SRC="resources/hub2-source"
OUT="/tmp/evidence"
mkdir -p "$OUT"

# --- prettify the minified classic bundles so we can grep with real context ---
prettify() {
  local in="$1" out="$2"
  if [ ! -f "$in" ]; then echo "MISSING: $in"; return; fi
  timeout 240 npx --yes prettier@3 --parser babel --no-config "$in" > "$out" 2>/dev/null \
    && echo "prettified $in -> $out ($(wc -l < "$out") lines)" \
    || { echo "prettier FAILED for $in; falling back to raw"; cp "$in" "$out"; }
}

echo "== prettifying classic bundles =="
prettify "$SRC/appUI.js" "$OUT/appUI.pretty.js"
prettify "$SRC/main.js"  "$OUT/main.pretty.js"
prettify "$SRC/helpers.js" "$OUT/helpers.pretty.js"
prettify "$SRC/hub2utils.js" "$OUT/hub2utils.pretty.js"
# Vue bundle: 3.3 MB. Prettify with a long timeout; window-grep if it fails.
prettify "$SRC/vue-hub2.min.js" "$OUT/vue.pretty.js"

PA="$OUT/appUI.pretty.js"
PM="$OUT/main.pretty.js"
PH="$OUT/helpers.pretty.js"
PU="$OUT/hub2utils.pretty.js"
PV="$OUT/vue.pretty.js"

# ctx FILE PATTERN BEFORE AFTER MAXBYTES  -> grep with context, bounded
ctx() {
  local f="$1" pat="$2" b="${3:-3}" a="${4:-18}" max="${5:-9000}"
  [ -f "$f" ] || { echo "(missing $f)"; return; }
  echo "----- grep -E '$pat'  in $(basename "$f") (-B$b -A$a) -----"
  grep -nEi -B"$b" -A"$a" "$pat" "$f" 2>/dev/null | head -c "$max" || true
  echo; echo
}

# win FILE PATTERN PRE POST NLINES MAXBYTES -> window extraction for single-line blobs
win() {
  local f="$1" pat="$2" pre="${3:-100}" post="${4:-320}" n="${5:-30}" max="${6:-9000}"
  [ -f "$f" ] || { echo "(missing $f)"; return; }
  echo "----- window '$pat'  in $(basename "$f") -----"
  grep -oEi ".{0,$pre}$pat.{0,$post}" "$f" 2>/dev/null | head -n "$n" | head -c "$max" || true
  echo; echo
}

############################################################
# 00 — inventory
############################################################
{
  echo "# hub2-source inventory"
  echo
  echo "## raw file sizes"
  ( cd "$SRC" && ls -la *.js 2>/dev/null | awk '{print $5"\t"$NF}' )
  echo
  echo "## prettified line counts"
  for p in "$PA" "$PM" "$PH" "$PU" "$PV"; do
    [ -f "$p" ] && printf "%8s lines  %s\n" "$(wc -l < "$p")" "$(basename "$p")"
  done
} > "$OUT/00-inventory.txt" 2>&1

############################################################
# 10 — classic endpoints (the RM wire surface)
############################################################
{
  echo "# Classic dynamicPage endpoints + call sites (appUI.js / main.js)"
  for pat in \
    'installedapp/update/json' \
    'installedapp/btn' \
    'installedapp/ssr' \
    'installedapp/configure' \
    'installedapp/collapseCallback' \
    'installedapp/createchild' \
    'installedapp/status' \
    'installedapp/disable' \
    'installedapp/forcedelete' ; do
    ctx "$PA" "$pat" 4 16 5000
    ctx "$PM" "$pat" 3 12 5000
  done
} > "$OUT/10-endpoints.txt" 2>&1

############################################################
# 20 — submitOnChange + the settings POST body it builds
############################################################
{
  echo "# submitOnChange / per-field re-POST + update/json payload construction"
  ctx "$PA" 'submitOnChange' 6 40 16000
  ctx "$PM" 'submitOnChange' 4 22 9000
  echo "## JSON.stringify / body building near update"
  ctx "$PA" 'JSON\.stringify' 3 10 6000
  ctx "$PM" 'JSON\.stringify' 2 8 5000
  echo "## FormData / serialize / .serializeArray / param building"
  ctx "$PA" 'serialize|FormData|\$\.param|new URLSearchParams' 3 12 6000
  ctx "$PM" 'serialize|FormData|\$\.param|new URLSearchParams' 2 10 5000
} > "$OUT/20-submitonchange.txt" 2>&1

############################################################
# 30 — button encoding (stateAttribute, /btn)
############################################################
{
  echo "# Button / stateAttribute encoding"
  ctx "$PA" 'stateAttribute' 5 26 12000
  ctx "$PA" 'function .*[Bb]tn|btnClick|appButton|AppButtons' 4 20 9000
  ctx "$PM" 'AppButtons|btnNext|formAction' 3 16 9000
} > "$OUT/30-buttons.txt" 2>&1

############################################################
# 40 — settings field serialization vocabulary
############################################################
{
  echo "# Field/setting serialization vocabulary (keys the wizard sends/reads)"
  for pat in \
    'settingType' 'multiple' "'enum'|\"enum\"" 'required' \
    'submitOnChange' 'defaultValue' 'inputType' 'data-submit' \
    'name=|"name"|.name' 'value=|"value"|.value' ; do
    win "$PA" "$pat" 60 160 14 2600
    win "$PM" "$pat" 60 160 10 2200
  done
} > "$OUT/40-payload-fields.txt" 2>&1

############################################################
# 50 — page navigation (nextPage, formAction, collapse)
############################################################
{
  echo "# Page navigation / wizard transitions"
  ctx "$PA" 'nextPage|btnNext|formAction|collapse' 3 16 9000
  ctx "$PM" 'nextPage|btnNext|formAction|createchild' 3 14 9000
} > "$OUT/50-pagenav.txt" 2>&1

############################################################
# 60 — classic RM compiled state (read-only ruleBuilderJson)
############################################################
{
  echo "# Classic RM compiled-state endpoint (ruleBuilderJson) + shape hints"
  ctx "$PA" 'ruleBuilderJson' 4 18 8000
  ctx "$PM" 'ruleBuilderJson' 4 18 8000
  win  "$PV" 'ruleBuilderJson' 80 360 20 8000
  echo "## shape tokens (broken / eval / parens / predCapabs / rendered)"
  win "$PV" 'predCapabs|"parens"|broken|ruleStateText|condIdx' 60 280 24 9000
} > "$OUT/60-readonly-rulebuilderjson.txt" 2>&1

############################################################
# 70 — Vue writable rules: Basic Rules + Visual Rule Builder 2.0
############################################################
{
  echo "# Vue SPA writable rule contracts (more-than-read-only)"
  win "$PV" 'ruleBuilder20Json' 90 420 24 11000
  win "$PV" '"ruleJson"|ruleJson:' 70 360 24 9000
  win "$PV" 'rulePaused' 60 240 16 5000
  win "$PV" 'saveOrUpdateJson' 80 320 16 6000
  win "$PV" 'VisualRuleBuilder20|VisualRuleBuilder|VRB[A-Z]' 50 260 24 8000
  win "$PV" 'BasicRulesApp|basicRule|basicRules' 50 260 20 7000
  win "$PV" 'ConditionsController|conditionType|actionType' 50 280 24 9000
} > "$OUT/70-vue-rules-write.txt" 2>&1

############################################################
# 80 — misc write/lifecycle endpoints
############################################################
{
  echo "# Misc lifecycle / direct endpoints"
  for pat in \
    'saveOrUpdateJson' 'installedapp/direct/swapDevice' \
    'installedapp/direct/hubVariables' 'installedapp/eventsJson' \
    'installedapp/statusJson' 'installedapp/json' 'createchild' ; do
    win "$PA" "$pat" 70 260 10 2600
    win "$PM" "$pat" 70 260 10 2600
    win "$PV" "$pat" 70 300 10 2600
  done
} > "$OUT/80-misc-endpoints.txt" 2>&1

############################################################
# 90 - Vue VRB 2.0 / Basic Rules WRITE contract (runner-only; vue is device-unsafe)
#      Now that vue.pretty.js exists (prettified on the runner), use rich -A context
#      instead of raw windows. This is the gating evidence for the highest-value
#      enhancement (structured-JSON rule write vs the classic wizard-walker).
############################################################
{
  echo "# Visual Rule Builder 2.0 / Basic Rules structured-JSON write contract"
  echo "## ruleBuilder20Json GET+POST + ruleJson document construction"
  ctx "$PV" 'ruleBuilder20Json' 6 44 18000
  echo "## ruleJson / rawJson document shape (trigger/condition/action node schema)"
  ctx "$PV" 'rawJson|ruleJson' 3 26 15000
  echo "## saveOrUpdateJson (app save) usage + body"
  ctx "$PV" 'saveOrUpdateJson' 4 22 9000
  echo "## ruleBuilderJson (classic compiled state) response field reads"
  ctx "$PV" 'ruleBuilderJson' 4 34 11000
  echo "## broken / eval / parens / predCapabs / ruleStateText field access"
  ctx "$PV" '\.broken|\.predCapabs|\.parens|ruleStateText|\.eval' 2 6 9000
  echo "## direct/hubVariables + direct/swapDevice POST bodies"
  ctx "$PV" 'direct/hubVariables|direct/swapDevice' 4 26 10000
  echo "## GATING Q: does VRB20 target classic RM, or only VRB/Basic child apps?"
  ctx "$PV" 'RuleMachine|ruleType|appTypeName|isVrb|vrbVersion|basicRule|BasicRule' 2 8 10000
} > "$OUT/90-vue-vrb-write.txt" 2>&1

############################################################
# A0 - RULE MACHINE 5.1 ONLY (classic dynamicPage) — focused sweep across ALL bundles
############################################################
{
  echo "# Rule Machine 5.1 data present in the hub2-source bundles"
  echo
  echo "## 1. Explicit 'Rule Machine' / Rule-5.x / Rule-4.x references (every bundle)"
  for f in "$PA" "$PM" "$PV" "$PH" "$PU"; do
    [ -f "$f" ] || continue
    echo "----- $(basename "$f") -----"
    grep -nEi 'rule[ ._-]?machine|rule[ ._-]?5|rule[ ._-]?4|ruleMachine|RM[ _]?5' "$f" 2>/dev/null | head -n 60 | head -c 9000 || true
    echo
  done
  echo "## 2. RM classic-app creation / install / configure routing"
  for f in "$PA" "$PM" "$PV"; do
    [ -f "$f" ] || continue
    echo "----- $(basename "$f") -----"
    grep -noEi '.{0,30}createchild.{0,70}' "$f" 2>/dev/null | head -n 12 | head -c 3000 || true
    grep -noEi '.{0,20}appName.{0,40}' "$f" 2>/dev/null | head -n 8 | head -c 2000 || true
    grep -noEi '.{0,15}/installedapp/configure[^"'\'' ]{0,60}' "$f" 2>/dev/null | head -n 16 | head -c 3000 || true
    echo
  done
  echo "## 3. dynamicPage wire ENVELOPE RM posts (the exact fields a Rule form submits)"
  for tok in formAction currentPage pageBreadcrumbs paramsForPage appTypeId appTypeName stateAttribute deviceList version 'settings\['; do
    echo "=== $tok ==="
    grep -noEi ".{0,45}${tok}.{0,70}" "$PA" 2>/dev/null | head -n 6 | head -c 1800 || true
    grep -noEi ".{0,45}${tok}.{0,70}" "$PM" 2>/dev/null | head -n 5 | head -c 1500 || true
    echo
  done
  echo "## 4. dynamicPage INPUT field-model vocabulary (the data shape of an RM form input)"
  echo "### appUI.pretty.js string-literal frequency (form/model keys):"
  grep -oE '"[a-zA-Z][a-zA-Z0-9_]{2,24}"' "$PA" 2>/dev/null | sort | uniq -c | sort -rn | head -90 || true
  echo
  echo "### main.pretty.js dynamicPage input/model property accesses:"
  grep -oE '"(submitOnChange|submitOnEveryChange|multiple|required|defaultValue|inputType|name|type|title|description|options|range|offerAll|disabled|textColor|backgroundColor|width|noBorder|image|hidden|placeholder|capability|attribute|command|device|append|displayDuringSetup|element|elem|section|sections|configPage|paragraph|href|page|nextPage|prevPage|install|uninstall|state|buttonSOC|inputClass|stateAttribute|hideable|target|collapse)"' "$PM" 2>/dev/null | sort | uniq -c | sort -rn | head -90 || true
} > "$OUT/A0-rule-machine.txt" 2>&1

############################################################
# B0 - BROAD rule/RM token census (not anchored on "rule machine")
############################################################
{
  echo "# Broad rule*/RM* token census across all bundles"
  echo
  echo "## 1. Every identifier/path containing 'rule' (case-insensitive), per file, with counts"
  for f in "$PA" "$PM" "$PV"; do
    [ -f "$f" ] || continue
    echo "===== $(basename "$f") ====="
    grep -oiE '[a-zA-Z0-9_./-]*rule[a-zA-Z0-9_./-]*' "$f" 2>/dev/null | sort | uniq -c | sort -rn | head -150 || true
    echo
  done
  echo "## 2. Standalone 'RM' (word-boundary, case-SENSITIVE) + RM<digit>/RM_<word>"
  for f in "$PA" "$PM" "$PV"; do
    [ -f "$f" ] || continue
    echo "===== $(basename "$f") ====="
    grep -noE '\bRM\b|\bRM[0-9_][A-Za-z0-9_]*' "$f" 2>/dev/null | sort | uniq -c | sort -rn | head -40 || true
    echo
  done
  echo "## 3. Classic Rule Machine internal vocabulary (server-side tokens), if present in bundles"
  for f in "$PA" "$PM" "$PV"; do
    [ -f "$f" ] || continue
    echo "===== $(basename "$f") ====="
    grep -noEi 'condActs|getIfThen|doActPage|doActsPage|selectActions|selectTrigger|STPage|predCapabs|actType|actSubType|trigEvents|RMUtils|capsForType|RelrDev|condEvents|ruleType|cancelTimers|cancelDelay|repeatActs|setVariable|privateBoolean|requiredExpression' "$f" 2>/dev/null | sort | uniq -c | sort -rn | head -50 || true
    echo
  done
} > "$OUT/B0-rule-token-census.txt" 2>&1

############################################################
# C0 - FULL main.js dynamicPage <form> + input template (for line-by-line impl diff)
############################################################
{
  echo "# main.js: the dynamicPage <form> hidden-field block"
  echo "#   (formAction / currentPage / pageBreadcrumbs / paramsForPage / appTypeId / appTypeName)"
  ctx "$PM" 'name="formAction"' 10 230 20000
  echo
  echo "# main.js: version + paramsForPage emission (separate template fn)"
  ctx "$PM" 'name="version"' 12 34 7000
  echo
  echo "# main.js: how a settings[...] input is rendered (elem -> HTML attributes)"
  ctx "$PM" 'name="settings\[' 40 80 20000
  echo
  echo "# appUI.js: full serialize loop + jsonSubmit + button POST + breadcrumbs + removeTags (mirror, device already has appUI.pretty.js)"
  ctx "$PA" 'var jsonSubmit = function' 0 130 16000
  ctx "$PA" 'postBody\[btn\]' 14 20 4000
} > "$OUT/C0-form-template-full.txt" 2>&1

############################################################
# D0 - exhaustive /app/ruleBuilderJson classic-RM compiled-shape hunt (all bundles)
############################################################
{
  echo "# /app/ruleBuilderJson — every reference + every compiled-state field consumer, all bundles"
  for f in "$PV" "$PA" "$PM"; do
    [ -f "$f" ] || continue
    echo "===== $(basename "$f") : ruleBuilderJson call sites ====="
    ctx "$f" 'ruleBuilderJson' 6 46 18000
  done
  echo
  echo "## compiled-state field tokens (a consumer would reveal the classic-RM response shape)"
  for f in "$PV" "$PA" "$PM"; do
    [ -f "$f" ] || continue
    echo "===== $(basename "$f") ====="
    for tok in 'broken' 'hasPredicate' 'predCapabs' 'condOper' 'capabsfalse' 'capabstrue' 'inUseConds' 'unusedConds' 'trigCustoms' 'trigDevs' 'condDevs' 'ruleStateText' 'whenNodes' 'thenNodes' 'elseNodes' 'promptHistory' '\.parens' '\.eval'; do
      hits=$(grep -oEi ".{0,45}${tok}.{0,70}" "$f" 2>/dev/null | head -n 10 | head -c 1800)
      [ -n "$hits" ] && { echo "--- $tok ---"; echo "$hits"; }
    done
    echo
  done
} > "$OUT/D0-rulebuilderjson-shape.txt" 2>&1

echo "== evidence files =="
ls -la "$OUT"
echo "== total evidence bytes (excl pretty sources) =="
cat "$OUT"/[0-9]*.txt | wc -c

# Drop the large pretty sources from the artifact; keep only bounded evidence
# plus the small classic pretties (useful, manageable) — but NOT the vue pretty.
rm -f "$OUT/vue.pretty.js" "$OUT/main.pretty.js"
echo "== final artifact contents =="
ls -la "$OUT"
