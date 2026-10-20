library(name: "McpNativeRulesLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine runtime tools, app deletion and retained backup compatibility for the MCP Rule Server.")

def _getAllToolDefinitions_partNativeRM() {
    return [
        // Native Rule Machine runtime and administrative tools.
        [
            name: "hub_list_rules",
            description: "List all Rule Machine rules (RM 4.x + 5.x, deduplicated by id) with each rule's id, label, and live `status` (`active`/`paused`/`stopped`/`disabled`).[[FLAT_TRIM]] Requires the Read master.[[/FLAT_TRIM]][[FLAT_TRIM]] `status` is `unknown` when the hub's app list is momentarily unreadable. CAVEAT: the runtime-STOPPED state is only visible here when the hub's list source decorates the label (many firmwares don't) — the authoritative stopped check is hub_get_rule_health's `stopped` field. For ONE rule's state, call hub_get_rule_health(appId) instead of listing every rule — it returns that rule's paused/disabled/stopped/broken directly. For the enabled/disabled state of NON-RM classic apps (Room Lighting, Notifier, Basic Rules, Button Controllers) use `hub_list_apps` (scope='instances'). Call `hub_get_tool_guide(section='builtin_app_tools_rules')` for the status-detection semantics and platform limitations on RM rule internals.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    labelFilter: [type: "string", description: "Case-insensitive label substring; applied before pagination."],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_call_rule",
            description: "Trigger one or more Rule Machine rules. Not destructive (invokes existing user-configured automation).[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]] Call `hub_get_tool_guide(section='builtin_app_tools_rules')` for action semantics.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to act on in one call"],
                    action: [type: "string", enum: ["rule", "actions", "stop", "start"], description: "Which RM action to invoke. Default: rule."],
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "hub_set_rule_paused",
            description: "Pause or resume one or more Rule Machine rules in one call (paused rules don't fire on triggers). paused=true pauses, paused=false resumes (idempotent on the hub). Pass an array of ruleIds to pause/resume a whole set at once — one RMUtils dispatch, e.g. a staged-migration cutover.[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to pause/resume together"],
                    paused: [type: "boolean", description: "true = pause the rule(s); false = resume."]
                ],
                required: ["ruleId", "paused"]
            ]
        ],
        [
            name: "hub_set_rule_private_boolean",
            description: "Set the private boolean of one or more Rule Machine rules to true or false (strict: accepts Boolean or lowercase string 'true'/'false' only).[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to set together"],
                    value: [type: "boolean", description: "Target value for the rule's Private Boolean."]
                ],
                required: ["ruleId", "value"]
            ]
        ],
        // Native classic-app CRUD (hub admin-layer, bypasses SmartApp parent-type check).
        // Generic across native automation app types — RM is the first registered type;
        // Room Lighting / Button Controllers / Basic Rules / Notifier / Groups+Scenes work
        // for edit + delete today (any classic-app appId), and join create as their entries
        // get added to _appTypeRegistry().
        [
            name: "hub_get_rule_health",
            description: """Inspect a rule's current state and return a structured health report.[[FLAT_TRIM]] Works for Rule Machine, Visual Rules Builder, and other classic apps (Button Controller, Basic Rule). This standalone read adds live eventSubscriptionCount/scheduledJobCount, runtime stopped, and status/markup fallbacks for paused. paused is true/false/null (unknown); explicit stopped state outranks display markup. Embedded health from write tools carries the structural verdict and compiled paused only, without these extra reads. With auto or ruleBuilderJson, Visual Rule labels preserve the raw own name; forcing configPage uses the rendered label.[[/FLAT_TRIM]] Run after every mutation. ok=false with unreadable=false means at least one issue was found (the issues list explains what); ok=false with unreadable=true means NEITHER source could be read (a transient fetch failure, or the app does not exist) -- a couldn't-check verdict, not evidence of breakage.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID to check (Rule Machine or Visual Rules Builder rule)."],
                    source: [type: "string", enum: ["auto", "ruleBuilderJson", "configPage"], description: "Which source(s) to read; default auto."]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "hub_list_rule_local_variables",
            description: "List a Rule Machine rule's LOCAL variables (per-rule, distinct from hub globals).[[FLAT_TRIM]] Requires the Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "The Rule Machine rule's installed-app id (the rule whose local variables to list)."]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "hub_delete_native_app",
            description: """Delete any classic native automation app (RM rule, Room Lighting instance, Button Controller / Button Rule, Basic Rule, Notifier, Group, Scene, etc.). Same endpoint family across all of them.[[FLAT_TRIM]] force=false (default) soft-deletes (hub refuses if the app has child apps/devices); force=true hard-deletes with no child safety checks.[[/FLAT_TRIM]]

BEFORE DELETE: full snapshot saved to File Manager (backup.backupKey).[[FLAT_TRIM]] Call hub_restore_backup (in hub_manage_backup) with that key to recreate the app with all its settings re-applied.[[/FLAT_TRIM]]

Requires Write master + confirm=true + recent hub backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID."],
                    force: [type: "boolean", description: "true = bypass child/device safety checks and force-delete. Default false."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["appId", "confirm"]
            ]
        ],
        [
            name: "hub_set_app_disabled",
            description: "Enable or disable any installed app (the admin UI red-X) without deleting it — reversible and preserves the app's config. For Rule Machine rules use hub_set_rule_paused instead. Write master only — no confirm/backup needed[[FLAT_TRIM]]; the disabled flag is read-back verified[[/FLAT_TRIM]].",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID to enable/disable (from hub_list_apps)."],
                    disabled: [type: "boolean", description: "true = disable the app (stop it running), false = enable it."]
                ],
                required: ["appId", "disabled"]
            ]
        ],
    ]
}

// Enable/disable any installed app via POST /installedapp/disable {id, disable} -- the documented
// Vue admin wire format (red-X). Reversible (re-enable with disabled=false). A POST that doesn't
// throw is NOT proof; the disabled flag is read back from /installedapp/json/<id>.
def toolSetAppDisabled(args) {
    def id = args?.appId
    if (id == null || !id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("appId must be a positive integer (got: '${id}')")
    }
    int appId = id.toString().toInteger()
    _requireUnprotectedAppMutation(appId, "enable or disable")
    if (args?.disabled == null) {
        throw new IllegalArgumentException("disabled is required (true to disable the app, false to enable it)")
    }
    boolean disable = (args.disabled == true || args.disabled?.toString() == "true")
    try {
        hubInternalPostJson("/installedapp/disable", groovy.json.JsonOutput.toJson([id: appId, disable: disable]))
    } catch (Exception e) {
        return [success: false, appId: appId, error: "POST /installedapp/disable failed: ${e.message}", note: "Verify the app id with hub_list_apps and retry."]
    }
    // Read the flag back. Distinguish empty body / GET exception / missing-key so a read-back
    // miss can't masquerade as a clean false-RED, and a missing 'disabled' key can't be read as
    // false (a silent false-GREEN). A bare GET exception means the WRITE may have committed.
    def observed = null
    def readErr = null
    try {
        def txt = hubInternalGet("/installedapp/json/${appId}")
        if (txt) {
            def parsed = new groovy.json.JsonSlurper().parseText(txt)
            if (parsed instanceof Map && parsed.containsKey("disabled")) {
                observed = (parsed.disabled == true)
            } else {
                readErr = "read-back response carried no 'disabled' field"
            }
        } else {
            readErr = "read-back returned an empty body"
        }
    } catch (Exception e) {
        readErr = e.message
        mcpLog("warn", "hub-admin", "hub_set_app_disabled ${appId}: read-back of /installedapp/json/${appId} failed: ${e.message}")
    }
    if (observed == disable) {
        mcpLog("info", "hub-admin", "Set installed app ${appId} disabled=${disable}")
        return [success: true, appId: appId, disabled: disable, message: "App ${appId} disabled flag is now ${disable}."]
    }
    if (observed == null) {
        return [success: false, appId: appId, error: "POST /installedapp/disable was accepted but the disabled state could not be confirmed (${readErr ?: 'unparseable read-back'}).", note: "The change may already be applied -- re-check with hub_list_apps before retrying; do NOT blindly re-POST."]
    }
    return [success: false, appId: appId, disabled: observed, error: "POST accepted but read-back shows disabled=${observed} (wanted ${disable}).", note: "The hub may not have committed the flag; retry, or check the app in the hub UI."]
}

// Enumerate Rule Machine rules via the official hubitat.helper.RMUtils API
// (RM 4.x + RM 5.x, deduplicated by id) and ghost-filter them against the live
// /hub2/appsList tree. Shared by hub_list_rules (which formats the rich list and
// reports ghosts) and _rmValidRuleIds (which needs the existence-safe id set for
// pre-write rule-target validation), so the RMUtils lookup + ghost-filter logic
// lives in exactly one place.
//
// RMUtils is an optional platform class -- absent on hubs that have never
// installed Rule Machine. Absence manifests as NoClassDefFoundError or
// ClassNotFoundException (both Error subclasses, uncaught by catch Exception),
// hence the catch Throwable + null-guarded .message; the caller classifies which
// Throwables are a quiet "RM not installed" vs a real failure to surface.
//
// Returns [combined, ghostIds, v4Error, v5Error, treeReadable]:
//   combined     -- id(String) -> entry map, ghost-filtered when the tree read
//   ghostIds     -- ids RMUtils reported that are absent from /hub2/appsList
//   v4Error/v5Error -- RM 4.x / 5.x lookup error strings (null on success)
//   treeReadable -- false when /hub2/appsList could not be read; combined is
//                   then UNfiltered (may still carry post-delete cache ghosts)
//   liveApps     -- the /hub2/appsList map (id -> [name, disabled, type]) used for the
//                   ghost filter AND hub_list_rules status enrichment; null when
//                   the tree was unreadable (treeReadable=false)
// Fetch a guide section in full for an inline meta-call on a tool that has no cursor of its own.
// Returns the same envelope hub_get_tool_guide does, with every page concatenated and the
// pagination bookkeeping stripped -- the caller asked for the reference, not a page of it.
private Map _rmCollectFilteredRmRules() {
    def combined = [:]
    def v4Error = null
    def v5Error = null

    try {
        def rules4 = hubitat.helper.RMUtils.getRuleList() ?: []
        rules4.each { r -> registerRmRule(combined, r, "4.x") }
    } catch (Throwable e) {
        v4Error = e.toString()
    }

    try {
        def rules5 = hubitat.helper.RMUtils.getRuleList("5.0") ?: []
        rules5.each { r -> registerRmRule(combined, r, "5.x") }
    } catch (Throwable e) {
        v5Error = e.toString()
    }

    // Filter RMUtils ghosts. RMUtils.getRuleList() reads a cached child
    // list inside the RM parent app and that cache keeps returning an id
    // for some time (seconds to minutes, non-deterministic) after the
    // rule's InstalledApp row is deleted — verified live on firmware
    // 2.5.0.123 for both /installedapp/delete/<id> and
    // /installedapp/forcedelete/<id>/quiet. Hub UI never shows these
    // because it reads /hub2/appsList, which is authoritative. Cross-
    // check against that tree and drop any rule whose id isn't in it.
    // If /hub2/appsList itself fails, fall through with unfiltered
    // output (treeReadable=false) so hub_list_rules keeps visibility on a
    // transient hub error and _rmValidRuleIds can treat it as cannot-verify.
    def ghostIds = []
    def treeReadable = false
    def liveApps = null
    try {
        liveApps = _collectLiveApps()
        if (liveApps != null) {
            treeReadable = true
            def filtered = [:]
            combined.each { id, entry ->
                def idInt
                try { idInt = (id instanceof Number) ? id.intValue() : id.toString().toInteger() }
                catch (Exception ignored) { idInt = null }
                if (idInt != null && liveApps.containsKey(idInt)) {
                    filtered.put(id, entry)
                } else {
                    if (idInt != null) ghostIds << idInt
                }
            }
            combined = filtered
        }
    } catch (Throwable e) {
        mcpLog("warn", "rm-interop", "_rmCollectFilteredRmRules: could not cross-check RMUtils output against /hub2/appsList (${e.message}); returning unfiltered")
    }
    return [combined: combined, ghostIds: ghostIds, v4Error: v4Error, v5Error: v5Error, treeReadable: treeReadable, liveApps: liveApps]
}

// Existence-safe set of Rule Machine rule ids (Integer) for pre-write target
// validation: the RMUtils rule list intersected with the live app tree. Returns
// null when the set cannot be trusted -- any RMUtils lookup error (Rule Machine never
// installed, or a transient one-sided v4/v5 failure) or the app-tree cross-check could
// not run -- so a caller treats "cannot verify" as skip-validation rather than a
// false-empty reject.
private Set _rmValidRuleIds() {
    def collected = _rmCollectFilteredRmRules()
    // Any RMUtils lookup error -- including a one-sided v4 or v5 failure -- makes the
    // enumeration incomplete, so the set cannot be trusted. On a 5.x-only hub the v4
    // getRuleList() legitimately returns [] with no error; if the v5 getRuleList("5.0")
    // then throws transiently, combined is empty but the rules are NOT verified-zero.
    // Requiring BOTH errors (the old && gate) would return that empty set as authoritative
    // and false-reject every existing target on the blip. RM-never-installed still errors
    // on both versions, so this keeps skipping there too.
    if (collected.v4Error || collected.v5Error) return null
    // App tree unreadable: membership is unverifiable (combined is unfiltered).
    if (!collected.treeReadable) return null
    def ids = [] as Set
    collected.combined.each { id, entry ->
        def idInt
        try { idInt = (id instanceof Number) ? id.intValue() : id.toString().toInteger() }
        catch (Exception ignored) { idInt = null }
        if (idInt != null) ids << idInt
    }
    return ids
}

// List all Rule Machine rules (RM 4.x + 5.x, deduped by id) via RMUtils, with
// post-delete cache ghosts filtered against /hub2/appsList. The RMUtils lookup and
// ghost filter live in _rmCollectFilteredRmRules (shared with the rule-target
// existence guard); this tool formats, paginates, and classifies the result.
def toolListRmRules(args) {
    def collected = _rmCollectFilteredRmRules()
    def combined = collected.combined
    def v4Error = collected.v4Error
    def v5Error = collected.v5Error
    def ghostIds = collected.ghostIds
    def treeReadable = collected.treeReadable
    def liveApps = collected.liveApps

    // Enrich each rule with its live enabled/paused/stopped/disabled status from the
    // /hub2/appsList tree (issue #359). When the tree was unreadable the rules are
    // returned unfiltered and carry no node data, so status is "unknown".
    combined.values().each { entry -> _rmAnnotateRuleStatus(entry, treeReadable, liveApps) }

    // combined.values() returns a Collection view in some Groovy versions; materialize
    // as a concrete List via toList() so subList in _paginateList is safe.
    def rules = combined.values().sort { it.label ?: "" }.toList()
    def labelFilter = args?.labelFilter?.toString()?.toLowerCase()
    if (labelFilter) rules = rules.findAll { (it.label ?: "").toString().toLowerCase().contains(labelFilter) }

    def cursor = args?.cursor
    def paged = _paginateList(rules, cursor, 50, "hub_list_rules")
    def result = [
        rules: paged.page,
        count: paged.page.size()
    ]
    if (cursor != null) {
        result.total = rules.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    if (ghostIds) {
        result.ghostsFiltered = ghostIds.sort()
        result.ghostNote = "RMUtils reported ${ghostIds.size()} rule id(s) that no longer exist in /hub2/appsList — these are post-delete RMUtils-cache ghosts (rule is already gone, the cache just hasn't caught up). Filtered out of the rules list."
    }
    if (!treeReadable && !rules.isEmpty()) {
        result.statusNote = "/hub2/appsList was unreadable, so each rule's status is \"unknown\" (enabled/paused/stopped/disabled could not be determined). Retry; if it persists the hub's internal API may need Hub Security credentials."
    }
    // Classify the failures. A "missing class" error (RM not installed) is quiet whether
    // the other version succeeded OR both versions failed the same way (both-absent path).
    // A non-missing-class error (e.g. timeout, internal platform issue) is always surfaced
    // so operators can investigate rather than get silent empty results.
    // Narrow to class-resolution failures only. Earlier versions treated any
    // MissingMethodException / "No signature of method" / unqualified "Cannot get property"
    // as quiet-missing, but those substrings appear in unrelated Groovy runtime errors
    // (e.g. a shape change in RMUtils' return value would surface as MME against
    // registerRmRule and get swept under the rug). Scope each substring tightly:
    //   - NoClassDefFoundError / ClassNotFoundException / "unable to resolve class":
    //     JVM-level class-resolution errors; unambiguous "RM not installed" signal.
    //   - "Cannot get property 'helper'": the Hubitat sandbox returns null for
    //     unresolved `hubitat.helper.X` namespace lookups; scoping to 'helper'
    //     limits the match to the hubitat.helper.RMUtils path specifically.
    //   - MissingMethodException together with getRuleList: covers the old-firmware
    //     case where RMUtils exists but lacks the getRuleList("5.0") overload —
    //     unrelated MMEs no longer get swallowed.
    def classMissingHint = { String msg ->
        if (!msg) return false
        if (msg.contains("NoClassDefFoundError") ||
            msg.contains("ClassNotFoundException") ||
            msg.contains("unable to resolve class")) return true
        if (msg.contains("Cannot get property") && msg.contains("'helper'")) return true
        if ((msg.contains("MissingMethodException") || msg.contains("No signature of method"))
            && msg.contains("getRuleList")) return true
        return false
    }
    def hardErrors = []
    if (v4Error && !classMissingHint(v4Error)) hardErrors << "v4=${v4Error}"
    if (v5Error && !classMissingHint(v5Error)) hardErrors << "v5=${v5Error}"

    def bothClassMissing = v4Error && v5Error &&
                           classMissingHint(v4Error) && classMissingHint(v5Error)

    if (rules.isEmpty() && bothClassMissing) {
        // Both RMUtils lookups failed with class-resolution errors. That's the
        // expected shape on a hub that has never installed Rule Machine; it's
        // not an error the caller needs to react to. Return an empty list with
        // an informational note and leave success unset so the result still
        // reads as a normal empty response.
        result.note = "Rule Machine not detected on this hub."
    } else if (rules.isEmpty() && v4Error && v5Error) {
        // Both calls failed but at least one with a non-missing-class shape --
        // unusual enough that operators should see the raw error.
        result.success = false
        result.error = "RMUtils calls failed: v4=${v4Error} v5=${v5Error}"
        result.note = "Rule Machine may not be installed on this hub."
    } else if (hardErrors) {
        // One version succeeded but the other had a non-missing-class error — surface it
        // as a warning without blocking the successful results.
        result.warning = "Partial RMUtils failure (results from the other version shown): ${hardErrors.join('; ')}"
    }
    return result
}

// Normalize a single RMUtils rule entry into a consistent map and register under combined[id].
//
// RMUtils.getRuleList() returns list entries in shapes that vary by RM version:
// - RM 5.x: single-entry Map `[<id>: "<label>"]` -- the key is the rule ID, the value is the label.
// The key may arrive as String or Integer depending on how the hub built the Map.
// - RM 4.x: Map with explicit fields `[id: <id>, label: "<label>", name: "...", type: "..."]`.
// - Raw primitive (int or String ID): defensive fallback for shapes not covered above.
private void registerRmRule(Map combined, def r, String version) {
    def id
    def label
    def name
    def type
    if (r instanceof Map) {
        if (r.containsKey("id")) {
            // RM 4.x explicit-fields shape
            id = r.id
            label = r.label ?: r.name
            name = r.name ?: r.label
            type = r.type
        } else if (r.size() == 1) {
            // RM 5.x single-entry shape: [<id>: "<label>"]
            def entry = r.entrySet().iterator().next()
            // entry.key may come back as String or Integer depending on how Hubitat built
            // the Map; coerce to Integer (via string) so downstream consumers get the
            // Integer id advertised in hub_list_rules' response schema.
            def rawKey = entry.key
            try {
                id = (rawKey instanceof Number) ? rawKey.toInteger() : rawKey.toString().toInteger()
            } catch (NumberFormatException coerceErr) {
                // Returning the raw key would silently violate the tool's
                // ruleId: integer schema (callers would see a String id in the
                // response and fail to dispatch hub_call_rule against it). Skip
                // the entry and surface the anomaly via logs instead.
                def keyType = rawKey instanceof Number ? 'Number' : rawKey instanceof String ? 'String' : 'other'
                mcpLog("warn", "rm-interop", "registerRmRule: non-integer ruleId '${rawKey}' (type=${keyType}) in RM ${version} list -- skipping entry")
                return
            }
            label = entry.value?.toString()
            name = label
        }
    } else if (r != null) {
        // Raw ID fallback
        id = r
    }
    if (id == null) return
    def key = id.toString()
    if (!combined.containsKey(key)) {
        combined.put(key, [
            id: id,
            label: label,
            name: name,
            type: type,
            rmVersion: version
        ])
    }
}

// Annotate an RM rule entry (in place) with disabled/paused booleans, a status summary,
// and requiredExpressionFalse. WHY decoration-detection (full algorithm in the
// builtin_app_tools guide): RMUtils exposes no paused or stopped boolean, so both are found
// only by diffing the app's /hub2/appsList name against its RMUtils label. A stopped rule
// reaches this function through EITHER of two channels and both are read here: the
// "(Stopped)" suffix can sit on the RMUtils LABEL while the appsList name stays clean, or it
// can sit in the appsList-name REMAINDER the "(Paused)" diff already computes. The diff is
// what proves a suffix is a runtime decoration rather than the rule's real name — a rule
// literally NAMED "... (Paused)"/"... (Stopped)" carries the suffix in BOTH strings, so
// neither channel fires. The suffix is stripped from the returned label/name in the SAME
// encoding the undecorated path returns (RMUtils entity-escaping preserved), never swapped
// for the HTML-stripped/decoded form the comparison uses. status is a precedence SUMMARY
// (disabled > stopped > paused > active); the booleans stay
// independent — a rule paused first then disabled keeps its decoration, so
// {disabled:true, paused:true, status:"disabled"} is truthful. "unknown" (booleans then
// omitted, never asserted false) covers both a null liveApps (whole tree unreadable) and
// this one rule's node lacking data.disabled / name / RMUtils label.
private void _rmAnnotateRuleStatus(Map entry, boolean treeReadable, Map liveApps) {
    def idInt
    try { idInt = (entry.id instanceof Number) ? entry.id.intValue() : entry.id?.toString()?.toInteger() }
    catch (Exception ignored) { idInt = null }
    def node = (treeReadable && idInt != null && liveApps != null) ? liveApps[idInt] : null
    if (node == null) {
        entry.status = "unknown"
        return
    }
    if (node.type instanceof String && node.type) {
        entry.type = node.type
        if (node.type.startsWith("Rule-5.")) entry.rmVersion = "5.x"
        else if (node.type.startsWith("Rule-4.")) entry.rmVersion = "4.x"
    }
    def disabledRaw = node.disabled
    def cleanApps = stripAppConfigHtml(node.name)
    def cleanRm = stripAppConfigHtml(entry.label)
    if (disabledRaw == null || cleanApps == null || cleanRm == null) {
        def missing = []
        if (disabledRaw == null) missing << "data.disabled"
        if (cleanApps == null) missing << "appsList name"
        if (cleanRm == null) missing << "RMUtils label"
        mcpLog("warn", "rm-interop", "_rmAnnotateRuleStatus: rule ${entry.id} has incomplete node data (missing ${missing.join(', ')}); status unknown")
        entry.status = "unknown"
        return
    }
    def disabled = disabledRaw == true
    def paused = false
    // The literal-name test is "appsList still starts with the WHOLE RMUtils label" -- the
    // same evidence channel B uses to tell decoration from name. Testing only that appsList
    // does not END with " (Stopped)" got case three wrong: a rule literally named
    // "X (Stopped)" that is ALSO paused has appsList "X (Stopped) (Paused)", which ends
    // with "(Paused)", so the suffix in its real name read as a runtime decoration. It was
    // then reported status:"stopped" AND had "(Stopped)" stripped from both label and name,
    // so a name-to-id lookup could no longer find the rule.
    boolean stopped = (cleanRm.endsWith(" (Stopped)") && !cleanApps.startsWith(cleanRm))
    if (cleanApps != cleanRm && cleanApps.startsWith(cleanRm)) {
        def remainder = cleanApps.substring(cleanRm.length())
        if (remainder.contains("(Paused)")) paused = true
        if (remainder.contains("(Stopped)")) stopped = true
        if (remainder.contains("(Required Expression false)")) entry.requiredExpressionFalse = true
    }
    if (stopped) {
        entry.label = _rmStripTrailingDecoration(entry.label, "(Stopped)")
        entry.name = _rmStripTrailingDecoration(entry.name, "(Stopped)")
    }
    entry.disabled = disabled
    entry.paused = paused
    entry.status = disabled ? "disabled" : (stopped ? "stopped" : (paused ? "paused" : "active"))
}

// Drop a trailing runtime decoration from a label WITHOUT re-encoding it: the caller's
// string keeps whatever escaping its source used, so a decorated and an undecorated rule
// come back in the same encoding. Returns the input unchanged when the suffix is absent.
private String _rmStripTrailingDecoration(Object raw, String suffix) {
    def s = raw?.toString()
    if (s == null) return null
    // RMUtils labels can carry trailing spaces, so match past them.
    def trimmed = s.replaceAll(/\s+$/, "")
    if (!trimmed.endsWith(suffix)) return s
    return trimmed.substring(0, trimmed.length() - suffix.length()).replaceAll(/\s+$/, "")
}

// Normalize a ruleId argument that may be a single id or a list of ids into a
// deduped List<Integer>. RMUtils.sendAction natively takes a LIST of rule ids,
// so the RMUtils-backed tools accept either shape and dispatch once.
//
// Multi-id batches also existence-check every id against the live RM rule list
// BEFORE any dispatch: the array form is sold as the staged-migration cutover,
// where one typo'd id silently left un-actioned (RMUtils is fire-and-forget and
// reports nothing per-rule) is the exact failure the batch exists to prevent.
// Single-id calls keep their long-standing no-validation behavior. The check
// skips when the rule list is unverifiable (_rmValidRuleIds returns null).
private Map _rmRuleIdListArg(Object raw) {
    if (raw == null) throw new IllegalArgumentException("ruleId is required")
    def rawList = (raw instanceof List) ? raw : [raw]
    if (rawList.isEmpty()) throw new IllegalArgumentException("ruleId array must not be empty")
    List<Integer> ids = []
    rawList.each { id ->
        // _rmCoerceRuleId, not normalizeRuleId: a JSON-number 400.7 would silently
        // TRUNCATE to rule 400 via toInteger() -- and then PASS the existence
        // check below, pausing the wrong rule with full confidence.
        def n = _rmCoerceRuleId(id)
        if (n == null) {
            throw new IllegalArgumentException("ruleId '${id}' is not an integer-valued rule id. Use hub_list_rules to find valid ids.")
        }
        if (!ids.contains(n)) ids << n
    }
    Boolean verified = null
    if (ids.size() > 1) {
        def valid = _rmValidRuleIds()
        if (valid != null) {
            verified = true
            def unknown = ids.findAll { !valid.contains(it) }
            if (unknown) {
                throw new IllegalArgumentException("ruleId array contains id(s) that are not existing RM rules: ${unknown.join(', ')}. Nothing was dispatched. Verify ids via hub_list_rules and re-issue the batch.")
            }
        } else {
            // Cannot verify (RMUtils error / app tree unreadable): the batch still
            // dispatches, but the caller is TOLD -- the tool description promises
            // an up-front existence check, and a silent skip would let a typo'd id
            // report success on a fire-and-forget dispatch.
            verified = false
            mcpLog("warn", "rm-interop", "_rmRuleIdListArg: multi-id existence check SKIPPED (rule list unverifiable) for ids ${ids.join(', ')}")
        }
    }
    return [ids: ids, idsVerified: verified]
}

// Trigger a Rule Machine rule via RMUtils.sendAction() (rule) or the RM page
// buttons for actions/stop/start. actions clicks runAction, the UI's "Run Actions"
// button; stop/start toggle stopRule (RMUtils has no startRule verb — the RM 5.1 UI
// uses the stopRule button as a toggle on state.stopped, and clicking it while
// stopped restarts the rule and resets the private boolean). The button route is
// the one the hub UI itself takes, so unlike RMUtils.sendAction it is not refused by
// the platform's per-app load limiter.
//
// Not destructive — invokes existing user-configured automation.
def toolRunRmRule(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    def idArg = _rmRuleIdListArg(args.ruleId)
    List<Integer> ruleIds = idArg.ids
    ruleIds.each { _requireUnprotectedAppMutation(it, "change rule runtime") }
    def action = args?.action ?: "rule"

    // start/stop route through the stopRule button click (a toggle on
    // state.stopped) rather than RMUtils.sendAction — verified live on
    // firmware 2.5.0.123 that clicking stopRule when state.stopped=false
    // sets it true (unsubscribe + cancel delays/repeats/waits) and when
    // state.stopped=true clears it + re-runs initialize() + resets the
    // private boolean. To keep the verb idempotent, we read state before
    // clicking and no-op if the rule is already in the target state.
    if (action == "stop" || action == "start" || action == "actions") {
        boolean runActions = (action == "actions")
        def perRule = { Integer id -> runActions ? _rmRunActionsButton(id) : _rmToggleStopped(id, action) }
        String verb = runActions ? "runAction button" : "stopRule toggle"
        if (ruleIds.size() == 1) {
            def single = perRule(ruleIds[0])
            // Every call echoes ruleIds -- scalar and array alike -- matching the
            // sendRmAction paths, so callers can read one key on every shape.
            if (single instanceof Map) single.ruleIds = ruleIds
            return single
        }
        // Per-rule toggle loop with a relay-budget checkpoint: each toggle is a
        // statusJson read + (maybe) a button click, so a long list can outlive the
        // response budget. _rmToggleStopped no-ops rules already in the target
        // state, so re-issuing with the remainder is safe.
        def results = []
        List remaining = []
        boolean budgetPaused = false
        for (int i = 0; i < ruleIds.size(); i++) {
            if (i > 0 && _timeBudgetExceeded(args?.__reqT0 as Long)) {
                budgetPaused = true
                remaining = ruleIds.subList(i, ruleIds.size()).collect { it }
                break
            }
            results << perRule(ruleIds[i])
        }
        List failed = []
        results.eachWithIndex { r, i -> if (r?.success != true) failed << ruleIds[i] }
        def out = [
            success: failed.isEmpty() && !budgetPaused,
            partial: budgetPaused || (!failed.isEmpty() && failed.size() < results.size()),
            ruleIds: ruleIds,
            rmAction: "${verb} x${results.size()}".toString(),
            results: results
        ]
        if (idArg.idsVerified != null) out.idsVerified = idArg.idsVerified
        if (failed) {
            out.failedRuleIds = failed
            def actionedCount = results.size() - failed.size()
            out.error = (actionedCount == 0)
                ? "${verb} failed for EVERY rule in the batch (${failed.join(', ')}); per-rule detail in results[].".toString()
                : "${verb} failed for rule(s) ${failed.join(', ')} -- the other ${actionedCount} rule(s) in the batch WERE actioned; per-rule detail in results[].".toString()
            out.note = runActions
                ? "Re-issue for the failed ids only: a repeat of a succeeded id runs that rule's actions again."
                : "Re-issue for the failed ids only. A repeat of a succeeded id is safe: the toggle no-ops rules already in the target state."
        }
        if (budgetPaused) {
            out.remainingRuleIds = remaining
            if (!out.error) out.error = "Response-budget checkpoint: rule(s) ${remaining.join(', ')} were not yet actioned."
            // Failed ids and un-reached ids are DIFFERENT sets -- the re-issue
            // guidance must carry both or a caller following it strands the failures.
            def reissueIds = (failed + remaining).unique()
            out.note = "Budget checkpoint -- re-issue with ruleId=[${reissueIds.join(', ')}] to finish${failed ? " (includes the ${failed.size()} failed id(s), not just the un-reached ones)" : ""}; already-actioned rules no-op safely."
        }
        return out
    }

    if (action != "rule") throw new IllegalArgumentException("Invalid action '${action}'. Must be 'rule', 'actions', 'stop', or 'start'.")
    def result = sendRmAction(ruleIds, "runRule", "hub_call_rule action=${action}")
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    return result
}

// Click the rule's Run Actions button (runAction on the main page): runs the action list
// directly, skipping condition evaluation, exactly as the hub UI does.
private Map _rmRunActionsButton(Integer ruleId) {
    try {
        _rmClickAppButton(ruleId, "runAction")
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=actions: runAction button click failed (${e.message})"]
    }
    return [success: true, ruleId: ruleId, rmAction: "runAction button"]
}

// Drive the RM 5.1 stopRule button — the same toggle the hub UI exposes
// for Stop/Start. `action` is one of "stop" or "start". Idempotent:
// clicking stopRule when state.stopped is already the target value would
// toggle it the wrong way (running -> stopped, stopped -> running), so
// we read state.stopped first and no-op if we're already there.
private Map _rmToggleStopped(Integer ruleId, String action) {
    def status
    try {
        status = _rmFetchStatusJson(ruleId)
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=${action}: cannot read rule state before toggle (${e.message})"]
    }
    def stoppedNow = _readAppStateBoolean(status, "stopped", false)
    def targetStopped = (action == "stop")
    if (stoppedNow == targetStopped) {
        return [
            success: true,
            ruleId: ruleId,
            rmAction: "noop",
            note: "Rule was already ${action == 'stop' ? 'stopped' : 'running (not stopped)'} -- stopRule button not clicked."
        ]
    }
    try {
        _rmClickAppButton(ruleId, "stopRule")
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=${action}: stopRule button click failed (${e.message})"]
    }
    return [
        success: true,
        ruleId: ruleId,
        rmAction: (action == "stop") ? "stopRule button -> state.stopped=true" : "stopRule button -> state.stopped=false (initialize + private boolean reset)"
    ]
}

// Pull a named Boolean out of statusJson.appState[]. Hubitat serializes
// Booleans in appState entries as the real `true`/`false` value, but
// occasionally as the string "true"/"false" -- treat both equivalently.
private Boolean _readAppStateBoolean(Map status, String name, Boolean fallback) {
    def entry = (status?.appState ?: []).find { it?.name == name }
    if (entry == null) return fallback
    def v = entry.value
    if (v instanceof Boolean) return v
    if (v == "true") return true
    if (v == "false") return false
    return fallback
}

// Fetch statusJson for {@code appId} and return the raw value of
// {@code state.editAct}, or {@code null} if the entry is absent.
//
// <p>RM 5.1 sets {@code state.editAct} to the action index whenever an
// action edit is in flight. While set, {@code delAct} and move-arrow
// clicks are silently no-oped by RM's {@code appButtonHandler}. This
// helper is used as a pre-flight guard in {@code _rmDeleteAction} and
// {@code _rmMoveAction} so callers get a descriptive error immediately
// rather than retrying for 10s before hitting the generic timeout.
//
// <p>RM is BELIEVED to clear a genuinely in-flight {@code state.editAct}
// after ~60s, but this is UNVERIFIED for a stale marker: one artificially set
// via a raw stateAttribute click was observed live (fw 2.5.1.135) NOT to clear
// after 5+ minutes. Treat the timeout as best-effort, not guaranteed. The value
// is typically an Integer (the action index) but may be serialized as a
// String by some firmware versions -- returned as-is without coercion.
private Object _rmGetStateEditAct(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def entry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    return entry?.value
}

// Pause or resume a Rule Machine rule via RMUtils.sendAction(pauseRule|resumeRule).
// paused=true pauses, paused=false resumes. Idempotent on the hub side. Backs the
// hub_set_rule_paused tool (verb-pair merge of the former pause/resume tools).
def toolSetRulePaused(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.paused == null) throw new IllegalArgumentException("paused (boolean) is required: true=pause, false=resume")
    Boolean paused
    if (args.paused instanceof Boolean) paused = args.paused
    else if (args.paused == "true") paused = true
    else if (args.paused == "false") paused = false
    else throw new IllegalArgumentException("paused must be boolean true/false (got: ${args.paused})")
    def idArg = _rmRuleIdListArg(args.ruleId)
    List<Integer> ruleIds = idArg.ids
    ruleIds.each { _requireUnprotectedAppMutation(it, "change rule runtime") }
    def result = paused ? sendRmAction(ruleIds, "pauseRule", "hub_set_rule_paused")
                        : sendRmAction(ruleIds, "resumeRule", "hub_set_rule_paused")
    // idsVerified: true = every id existence-checked before dispatch; false = the
    // check was SKIPPED (rule list unverifiable) and this fire-and-forget batch
    // may include ids that do not exist. Absent on single-id calls.
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    // Echo the applied pause state so callers can confirm the outcome without a
    // follow-up read. RMUtils pause/resume is fire-and-forget, so this is the
    // requested/applied value, not a hub read-back.
    if (result instanceof Map) result.paused = paused
    return result
}

// Set a Rule Machine rule's private boolean (used by its conditions) via
// RMUtils.sendAction(setRuleBooleanTrue|setRuleBooleanFalse).
//
// Strict-coercion policy: accepts ONLY Boolean true/false or the canonical
// lowercase strings "true"/"false". Other truthy-looking values — 1, 0,
// "True", "TRUE", "yes", "no", "on", "off" — are rejected with
// IllegalArgumentException. Reason: silently coercing "yes" to true (or 1
// to true) risks the AI sending the wrong boolean when a rule's behaviour
// depends on the boolean (e.g. arming a security path). The ambiguity is
// worse than the friction of requiring explicit true/false.
def toolSetRmRuleBoolean(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.value == null) throw new IllegalArgumentException("value (boolean) is required")
    // Accept Boolean or the canonical lowercase strings 'true'/'false' only. Reject other
    // truthy-looking values (1, 'yes', 'True') to avoid silently setting the wrong boolean.
    Boolean resolved = null
    if (args.value instanceof Boolean) {
        resolved = args.value
    } else if (args.value == "true") {
        resolved = true
    } else if (args.value == "false") {
        resolved = false
    } else {
        throw new IllegalArgumentException("value must be boolean true/false (or the string 'true'/'false'), got: ${args.value}")
    }
    def rmAction = resolved ? "setRuleBooleanTrue" : "setRuleBooleanFalse"
    def idArg = _rmRuleIdListArg(args.ruleId)
    idArg.ids.each { _requireUnprotectedAppMutation(it, "set rule private boolean") }
    def result = sendRmAction(idArg.ids as List, rmAction, "hub_set_rule_private_boolean value=${resolved}")
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    return result
}

// Shared sendAction wrapper. Returns a consistent success/error result map.
//
// Invariant: the 4-arg form `sendAction([ids], action, appLabel, "5.0")` dispatches
// to the RM 5.x handler and is the canonical call for both RM 4.x and RM 5.x rules.
// The 3-arg form `sendAction([ids], action, appLabel)` reaches only RM 4.x and is
// used as a fallback for very old firmware that predates the version-discriminator
// overload (see sendRmActionFallback). Background on the RM API shape:
// https://community.hubitat.com/t/rule-machine-api/7104
private Map sendRmAction(List<Integer> ruleIds, String rmAction, String logContext) {
    def appLabel = app?.label ?: "MCP Rule Server"
    try {
        hubitat.helper.RMUtils.sendAction(ruleIds, rmAction, appLabel, "5.0")
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule(s) ${ruleIds.join(', ')}")
        def result = [success: true, ruleIds: ruleIds, rmAction: rmAction]
        if (ruleIds.size() == 1) result.ruleId = ruleIds[0]
        return result
    } catch (MissingMethodException e) {
        return sendRmActionFallback(ruleIds, rmAction, appLabel, logContext, e)
    } catch (NoSuchMethodError e) {
        return sendRmActionFallback(ruleIds, rmAction, appLabel, logContext, e)
    } catch (Throwable e) {
        // Everything else — NoClassDefFoundError (RM not installed), timeouts, NPEs
        // inside RM internals, invalid ruleId — propagates through a single error
        // response without a second attempt. Retrying a transient failure would
        // double-fire the action if the first call partially succeeded.
        def m = e.message ?: e.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule(s) ${ruleIds.join(', ')}: ${m}")
        return [success: false, error: "RMUtils.sendAction failed: ${m}", note: _rmSendActionErrorNote(rmAction, ruleIds, m)]
    }
}

// Single source of truth for the RMUtils load-limiter marker substring (matched
// case-insensitively). RMUtils surfaces this text when the hub's per-app load limiter
// has tripped; centralized so a wording change is one edit, not a scatter of literals.
private String _rmExcessiveLoadMarker() { "excessive hub load" }

// Build the actionable note for a failed RMUtils.sendAction. When RMUtils reports the hub's
// per-app load limiter, steer recovery correctly: that limiter is STICKY -- it does NOT lift when
// hub load drains, so an immediate retry of the same call just fails again. An app disable/enable
// only clears the block on the app INSTANCE and the platform's load counters survive it, so the
// retry after a bounce can re-trip immediately (observed live); only a hub REBOOT resets those
// counters. On a pause/resume call the direct page-button escape
// bypasses RMUtils and is load-immune, so it IS the real recovery. Pause and resume share a
// SINGLE toggle button, pausRule, whose title flips between "Pause" and "Resume" with the rule's
// current run state (the same one-button toggle pattern as stopRule); clicking pausRule on a
// paused rule resumes it, so both the pause and resume cases steer to the same pausRule button.
// Substring is scoped tightly to RMUtils' load-message text so unrelated failures keep the plain note.
private String _rmSendActionErrorNote(String rmAction, List<Integer> ruleIds, String message) {
    def base = "Verify the ruleId is valid (use hub_list_rules) and Rule Machine is installed."
    if (ruleIds.size() > 1) {
        // A LIST dispatch that threw may have actioned part of the set before
        // failing -- a reflex retry double-fires runRule/runRuleAct actions.
        base = "${base} NOTE: this was a LIST dispatch (${ruleIds.join(', ')}) -- part of the set may already have been actioned; verify per-rule state via hub_list_rules before retrying (a blind retry re-fires already-actioned rules)."
    }
    if (message && message.toLowerCase().contains(_rmExcessiveLoadMarker())) {
        if (rmAction == "pauseRule" || rmAction == "resumeRule") {
            def verb = (rmAction == "resumeRule") ? "resume" : "pause"
            def perRule = (ruleIds.size() == 1) ? "hub_set_rule(operation:'form', rule_id:${ruleIds[0]}, args:[steps:[[kind:'open']]], confirm:true), then use the returned pause control"
                                                : "hub_set_rule(operation:'form', rule_id:<id>, args:[steps:[[kind:'open']]], confirm:true), then use the returned pause control per rule (${ruleIds.join(', ')}) -- CHECK EACH RULE'S CURRENT STATE FIRST via hub_list_rules: the batch went out in one RMUtils dispatch and part of it may already have been actioned, and pausRule is a toggle, so clicking it on a rule the batch already ${verb}d UNDOES that rule"
            return "RMUtils hit the hub's per-app load limiter (sticky -- retrying does not clear it; an app disable/enable clears only the block on the app instance and the platform's load counters can re-trip it at once, so only a hub reboot fully resets it). To ${verb} the rule now, drive the page button directly with ${perRule}, which bypasses RMUtils and is load-immune (pausRule is a single toggle -- clicking it on a paused rule resumes it). ${base}"
        }
        return "RMUtils hit the hub's per-app load limiter. It is sticky -- it does NOT lift when load drains and an immediate retry fails the same way. An app disable/enable clears the block on the app instance but not the platform's load counters, so it can re-trip immediately; a hub reboot is what resets them. Clear it, then reissue. ${base}"
    }
    return base
}

// 3-arg fallback invoked only when the 4-arg sendAction raised a signature-mismatch
// shape (MissingMethodException / NoSuchMethodError) — very old firmware that
// predates the version-discriminator overload. Any other Throwable from the 4-arg
// call propagates directly in sendRmAction above.
private Map sendRmActionFallback(List<Integer> ruleIds, String rmAction, String appLabel, String logContext, Throwable original) {
    try {
        hubitat.helper.RMUtils.sendAction(ruleIds, rmAction, appLabel)
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule(s) ${ruleIds.join(', ')} (3-arg fallback)")
        def result = [success: true, ruleIds: ruleIds, rmAction: rmAction, fallback: "3-arg"]
        if (ruleIds.size() == 1) result.ruleId = ruleIds[0]
        return result
    } catch (Throwable e2) {
        def m1 = original.message ?: original.toString()
        def m2 = e2.message ?: e2.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule(s) ${ruleIds.join(', ')}: 4-arg=${m1}, 3-arg=${m2}")
        return [success: false, error: "RMUtils.sendAction failed: ${m2}", note: "4-arg attempt also failed: ${m1}. ${_rmSendActionErrorNote(rmAction, ruleIds, m2)}"]
    }
}

// Resolve the page-transition button to click after a main-page settings
// write commits, for the app type whose live config reports `configAppName`
// (config.app.appType.name -- the child appName the registry keys on).
// Returns "updateRule" (the RM framework default) for unrecognized or
// RM-family types, and the registry's explicit `commitButton` (which may be
// null) for types that override it. A null return means "no commit click" --
// the app's inputs are submitOnChange and clicking updateRule would poison
// the render (e.g. Basic Rule's "For input string: updateRule").
// Repair hint for a session-end mainPage Done that did not commit. A Done the page itself refused
// (uiBlocked) posted nothing: the fix is the named fields, not a commit click.
private String _resolveCommitButton(String configAppName) {
    if (!configAppName) return "updateRule"
    // The Room Lighting parent has no updateRule button and no create entry of its own. A settings write on
    // the Room Lighting parent (e.g. newScene) already does its work; clicking updateRule there
    // throws MissingMethodException in its appButtonHandler (verified live, fw 2.5.1.181).
    if (configAppName == "Room Lighting") return null
    // The hub reports Visual Rule children versioned ("Visual Rule Builder 2.0"); the registry holds
    // the bare family name. Exact match first, then the version-stripped name.
    def versioned = (configAppName.toString() =~ /^(.+) [0-9]+(?:\.[0-9]+)*$/)
    def bare = versioned.matches() ? (versioned[0] as List)[1].toString() : null
    def match = _appTypeRegistry().find { typeKey, reg -> reg.appName == configAppName } ?:
                (bare != null ? _appTypeRegistry().find { typeKey, reg -> reg.appName == bare } : null)
    if (match != null && match.value.containsKey("commitButton")) return match.value.commitButton
    return "updateRule"
}

// Detect whether the given configPage still carries an open RM-style
// wizard editor scaffold (any input whose name matches the well-known
// trigger/condition/action edit-scaffold patterns). Used by
// hub_set_native_app to decide whether a wizard-Done button (`hasAll`,
// `actionDone`, ...) actually closed the editor or whether it needs a
// second click to commit. Verified live on firmware 2.5.0.123: the
// first wizard-Done click frequently leaves the scaffold in place and
// a second click of the same button finalizes the commit.
//
// Patterns recognized:
// isCondTrig.<N>   trigger-N "is conditional?" toggle (always present
// while a trigger row is open for editing)
// tCapab<N>        trigger-N capability picker
// tDev<N>          trigger-N device picker
// tstate<N>        trigger-N state value
// AlltDev<N>       trigger-N "all of these?" toggle
// stays<N>         trigger-N "and stays?" toggle
// editAct<N>       action-N edit scaffold
// editCond<N>      condition-N edit scaffold
private Map _collectLiveApps() {
    def responseText
    try {
        responseText = hubInternalGet("/hub2/appsList")
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList fetch failed (${e.message})")
        return null
    }
    if (!responseText) return null
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList parse failed (${e.message})")
        return null
    }
    if (!(parsed instanceof Map) || !(parsed.apps instanceof List)) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList is missing its apps list")
        return null
    }
    def apps = [:]
    boolean complete = true
    def walk
    walk = { node ->
        // A leaf may carry "children": null; only a present non-List value is malformed.
        if (!(node instanceof Map) || (node.data != null && !(node.data instanceof Map))
                || (node.children != null && !(node.children instanceof List))) {
            complete = false
            return
        }
        def idVal = node.data?.id != null ? node.data.id : node.id
        if (idVal != null) {
            try {
                // Store the RAW disabled value (may be null when the key is absent) —
                // coercing to == true here would erase the "field missing" signal that
                // _rmAnnotateRuleStatus needs to classify the rule as per-entry unknown.
                if (!(idVal.toString() ==~ /[1-9][0-9]*/)) throw new IllegalArgumentException("Invalid app ID")
                apps.put(idVal as Integer, [name: node.data?.name, disabled: node.data?.disabled, type: node.data?.type])
            } catch (Exception ignored) { complete = false }
        } else if (node.data) {
            // An idless structural container is safe only when all its children can be read;
            // absent children are an empty container, not a malformed one.
            complete = false
        }
        (node?.children ?: []).each { walk(it) }
    }
    parsed.apps.each { walk(it) }
    if (!complete) mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList contains malformed app nodes; absence cannot be established")
    return complete ? apps : null
}

// Normalize an atTime string to the form RM 5.1 expects:
// {@code YYYY-MM-DDTHH:mm:ss.SSS+HHMM} (millis + numeric zone offset,
// no colon in the offset, e.g. {@code 2026-04-28T03:00:00.000-0500}).
//
// <p>RM 5.1's {@code selectTriggers} page render throws
// {@code java.text.ParseException: Unparseable date: ...} and enters a
// broken state if the stored {@code atTime<N>} value lacks the millis
// fraction or the timezone offset. Any subsequent trigger-add then also
// fails because the page can't render. Normalizing here prevents the
// cascade.
//
// <p><b>Format choice has SEMANTIC consequences for trigger recurrence:</b>
// <ul>
// <li>HH:mm form -- trigger fires DAILY at that wall-clock time (every day).</li>
// <li>Any ISO datetime form -- trigger fires ONCE on the specific date.</li>
// </ul>
//
// <p>Accepted input forms:
// <ol>
// <li>{@code 17:00} or {@code 5:00} -- HH:mm form; passes through unchanged.
// RM 5.1 stores this verbatim and renders the trigger as
// "When time is HH:MM AM/PM" with daily recurrence. Verified live on
// firmware 2.4.4.156: rule with {@code atTime1="22:00"} fires every day
// at 10 PM (existing rule id 825 used as reference).</li>
// <li>{@code 2026-04-28T03:00:00.000-0500} -- explicit numeric offset; normalized
// to UTC equivalent (same instant: {@code 2026-04-28T08:00:00.000+0000}). One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00} -- no millis, no tz; hub local tz applied. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00.000} -- millis present, no tz; hub local tz applied. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00-0500} -- no millis, explicit offset; normalized to UTC. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00Z} -- Zulu shorthand; normalized to +0000. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00.000Z} -- Zulu with millis; normalized to +0000. One-shot dated.</li>
// </ol>
//
// <p>Throws {@code IllegalArgumentException} for input that does not
// match any of the above forms (ASCII-only message, echoes the bad value).
// Internal _rm helper -- not part of the tool surface.
private Integer _rmCoerceRuleId(Object id) {
    try {
        if (id instanceof Number) {
            return (id.doubleValue() == id.longValue()) ? id.intValue() : null
        }
        def s = id?.toString()?.trim()
        if (!s) return null
        def bd = new BigDecimal(s)   // throws on non-numeric -> caught -> null
        return (bd.stripTrailingZeros().scale() <= 0) ? bd.intValue() : null
    } catch (Exception ignored) { return null }
}

// Fail loud, pre-write, when a rule-targeting action names a rule id that does not
// resolve to an existing Rule Machine rule. RM 5.1 stores an unknown id verbatim
// into the action's rule field and bakes a dangling reference that never fires and
// renders broken, with no error at write time. Resolving each id against the
// existence-safe rule-id set (the RMUtils rule list confirmed against /hub2/appsList)
// up front keeps the refusal pre-write, matching the fail-loud style of the other
// pre-write guards.
//
// validRuleIds lets a bulk caller resolve the set once and thread it to every item
// (avoids one fetch per action); a single-action caller passes null and the set is
// resolved lazily here. When the set cannot be determined -- RMUtils absent or the
// app-tree cross-check failed, both surfaced as null -- treat it as "cannot verify"
// and let the write proceed (a genuinely bogus id still bakes broken) rather than
// synthesize a misleading "rule does not exist" on a hub blip; mirrors the transient-
// skip stance of _rmValidateDeviceIdsExist. An EMPTY (non-null) set is NOT a blip --
// the rule list was read successfully and has zero valid rules -- so every rule target
// is genuinely invalid and is rejected fail-loud (only null, cannot-verify, skips).
//
// This guard wraps a bare scalar into a list (a caller may pass ruleIds as a single
// integer), BECAUSE ruleIds is not contractually required to be an array here -- the
// sibling _rmValidateDeviceIdsExist instead SKIPS a non-List input, because a bare
// integer deviceId is the very bug it exists to catch (RM stores {N:null} for it), so
// silently ignoring the scalar is the safe stance there. For a rule target the scalar
// is a legitimate single-rule reference, so it is validated rather than skipped.
private List _rmCollectTriggerIndices(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def out = []
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^tCapab(\d+)$/)
            if (m.matches()) out << (m[0][1] as Integer)
        }
    }
    return out
}

// Scan an RM rule's appSettings for tCapab<N> entries and return a Map of
// trigger index -> committed capability string, read from the SAME statusJson
// the index scan uses. tCapab is an enum picker, so its appSettings value is the
// plain capability string (unlike device pickers, whose value is null with the
// ids in deviceIdsForDeviceList). Lets a caller resolve both the live trigger set
// and a trigger's capability from ONE hub fetch; a tCapab entry with an absent
// value maps to null, which the family guard treats as unresolved -- fail safe.
private List _rmActionIndicesFromSettings(Map status) {
    def out = []
    def seen = [] as Set
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^act(?:Type|SubType)\.(\d+)$/)
            if (m.matches()) {
                def idx = (m[0][1] as Integer)
                if (seen.add(idx)) out << idx
            }
        }
    }
    return out
}

// Rows that hold an action or a structural keyword -- actType.<N> or actSubType.<N> with a value.
// Excludes rows whose actType/actSubType are both blank, which RM leaves behind after a delete or
// a cancel. This answers "what is on this rule at the settings level": a row the compiled
// actionList never listed still occupies its index, and ignoring it leaves a permanent orphan.
private List _rmLiveActionIndicesFromSettings(Map status) {
    def live = [:]
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (!n) return
        def m = (n =~ /^act(?:Type|SubType)\.(\d+)$/)
        if (!m.matches()) return
        def idx = (m[0][1] as Integer)
        if (s?.value?.toString()?.trim()) live.put(idx, true)
    }
    return live.keySet().sort()
}

// Tri-state disabled status for restore and the disabled-app guard: null means unverified.
Boolean _rmIsAppDisabled(Integer appId) {
    String why
    // Two attempts. A caller that gets null PROCEEDS (a blip must not block edits on a healthy
    // hub), so an unreadable first attempt is the one case where the gate silently stops
    // protecting: on a rule that really is disabled, RM then no-ops the write and the caller
    // reports success having changed nothing. One cheap retry closes the transient-blip window
    // without turning a hiccup into a refusal.
    for (int attempt = 0; attempt < 2; attempt++) {
        if (attempt > 0) { try { pauseExecution(250) } catch (Exception ignore) { } }
        why = null
        try {
            def txt = hubInternalGet("/installedapp/json/${appId}")
            if (!txt) {
                why = "empty /installedapp/json body"
            } else {
                def parsed = new groovy.json.JsonSlurper().parseText(txt)
                if (parsed instanceof Map && parsed.containsKey("disabled")) return parsed.disabled == true
                why = "/installedapp/json carried no 'disabled' key"
            }
        } catch (Exception e) {
            why = "read failed (${e.message})"
        }
    }
    mcpLog("warn", "rm-native", "_rmIsAppDisabled(${appId}): ${why} on two attempts -- disabled state UNKNOWN, proceeding")
    return null
}

void _rmRejectDisabledAppEdit(Integer appId, String opName) {
    if (_rmIsAppDisabled(appId) != true) return
    throw new IllegalArgumentException("${opName} blocked: rule ${appId} is DISABLED. Hubitat does not allow editing a disabled app -- its configuration page renders only \"App is disabled\", so there is no wizard to drive. This is intended platform behavior, not an error in the rule. To edit it: hub_set_app_disabled(appId=${appId}, disabled=false), make the change, then disable it again if you want it left parked. RM is not touched.")
}

// Delete a single action at a specific index. Verified live
// by inspecting the live UI HTML on selectActions: the per-row trash
// button has data-stateAttribute='delAct' and the form-input name is
// just the action index as a digit (e.g. name='2' for action 2). RM's
// appButtonHandler dispatches on the stateAttribute and uses the name
// to identify which action to delete.
//
// After delete, RM does NOT renumber remaining actions — indices are
// preserved with gaps. Subsequent addAction picks the next free index
// via _rmActionIndicesFromSettings' max+1 logic (the settings scan, deliberately -- it must
// see un-baked rows the compiled list cannot).
//
// Pre-flight refuses three things before the delAct click goes out:
// (a) the requested index does not exist on the rule,
// (b) the rule has a stuck state.editAct from a prior interrupted
// edit (RM silently no-ops delAct clicks in that state), and
// (c) the action is a structural opener/closer (IF / ELSE-IF / ELSE /
// END-IF / Repeat / While / End-Repeat) and removing it would
// introduce a new structural-balance issue (compared as a set diff
// so deletions that merely improve an already-broken rule are
// still allowed). All three throw IllegalArgumentException /
// IllegalStateException — no RM mutation is sent.
//
// Post-click verification uses a retry loop to absorb RM 5.1's
// asynchronous button-handler dispatch. The click returns HTTP 200 before
// the deletion has propagated to appSettings, so a single immediate fetch
// can falsely report the action still present. Up to 5 attempts are made
// with increasing delays (1s, 1.5s, 2s, 3s) for a max sleep budget of
// 7.5s (wall-clock under the relay ceiling including the five HTTP
// fetches), with ONE verified re-click at the third attempt when the row
// is still present, and near-zero extra latency on the common success
// path (first check passes immediately). That budget covers typical and slow hub propagation
// observed in live-hub runs; on retry exhaustion the throw directs the
// caller to verify via hub_get_app_config since the deletion may complete
// post-response. See source comment below for the original race description.
// `skipStructuralCheck` skips the per-row balance refusal for a caller that has already checked
// balance itself: the in-flight rollback below (a row this call wrote and never committed, so it
// is not part of the block structure) and a restore removing a whole set of rows whose combined
// removal leaves the rule balanced (each half of an IF/END-IF pair alone would be refused).
Map _rmDeleteAction(Integer appId, Integer actionIdx, boolean skipStructuralCheck = false) {
    // Single statusJson fetch shared by all three pre-flight checks below;
    // before the refactor each helper (_rmCollectActionIndices,
    // _rmGetStateEditAct, structural pre-flight) called _rmFetchStatusJson
    // independently, tripling the per-delete read load on the hub.
    def status = _rmFetchStatusJson(appId)
    def settingsByName = (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
    // Existence check reads SETTINGS, not the compiled action list: a row that
    // was written but never baked exists only here, and refusing to delete it
    // would strand it. The status fetch above is reused.
    def beforeIndices = _rmActionIndicesFromSettings(status)
    if (!beforeIndices.contains(actionIdx)) {
        throw new IllegalArgumentException("removeAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}")
    }
    // Pre-flight: if state.editAct is set, RM will silently no-op the delAct
    // click. Detect and fail immediately with a descriptive error rather than
    // burning 10s of retries and surfacing a confusing timeout message.
    def editActEntry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    def stuckEditAct = editActEntry?.value
    if (stuckEditAct != null) {
        throw new IllegalStateException("removeAction(${actionIdx}) blocked: rule ${appId} has state.editAct=${stuckEditAct} set from a prior interrupted action edit. RM 5.1 silently no-ops delAct clicks until this clears. Recovery options: (a) wait ~60s and retry (RM's stale-state timeout -- UNVERIFIED for this marker: an artificially-set editAct observed live did NOT clear after 5+ minutes); (b) open the rule in the Hubitat UI and finish or cancel the open action editor. Verified live on fw 2.5.1.135: cancelBtn/cancelAct clicks, updateRule, and an in-place hub_restore_backup do NOT clear a stuck editAct (restore-in-place rewrites settings, not app state) -- if (a) and (b) fail, delete the rule, then hub_restore_backup(scope='source', backupKey='<a recent rm-rule snapshot for this rule>', confirm=true), which RECREATES it with fresh state.")
    }
    // Structural pre-flight: refuse if the deleted index is a block
    // opener/closer AND removing it would introduce a new imbalance not
    // present in the current rule. Set-diff (projected MINUS current)
    // rather than size-only — size comparison allows damage-shuffling on
    // already-broken rules (e.g. swapping "Repeat never closed" for
    // "END-IF closes a Repeat — mismatched" keeps the count flat). The
    // set diff still allows deletions that strictly improve a broken
    // rule, because every projected issue is also in current.
    // Keyed off actSubType ALONE: RM 5.1's UI persists no actType.<N> on
    // ELSE / ELSE-IF / END-IF rows, so also requiring actType would let a
    // UI-built closer skip the refusal and silently unbalance the rule.
    def sType = settingsByName["actSubType.${actionIdx}".toString()]?.value?.toString()
    def structuralSubTypes = ["getIfThen", "getElseIf", "getElse", "getEndIf", "getRepeat", "getWhile", "getStopRepeat"]
    if (sType in structuralSubTypes && !skipStructuralCheck) {
        // Both sides walk the rule's OWN actions. A leftover settings row is not
        // part of the rule's structure, and the set-diff does not reliably cancel
        // it out: a stale closer can absorb the imbalance a real deletion creates,
        // so current and projected both come back clean and the refusal never fires.
        // One spelling for both structural refusals below.
        def humanKind = [
            "getIfThen": "IF",
            "getElseIf": "ELSE-IF",
            "getElse": "ELSE",
            "getEndIf": "END-IF",
            "getRepeat": "Repeat",
            "getWhile": "Repeat-While",
            "getStopRepeat": "End-Repeat"
        ][sType] ?: sType
        def orderedIndices = _rmOrderedActionIndices(appId)
        if (orderedIndices == null) {
            // Without the compiled order the only guard left is the whole-settings scan, where a
            // leftover closer can mask the imbalance this deletion creates -- a structural row
            // could be deleted past a check that never saw the real block structure. Refuse, as
            // moveAction and modifyAction do; a non-structural row (above) is unaffected.
            throw new IllegalStateException("removeAction(${actionIdx}) blocked: action ${actionIdx} is a structural ${humanKind} row and the rule's compiled action order (ruleBuilderJson actionList) is unavailable, so the deletion's effect on block balance cannot be verified -- the settings scan can be masked by leftover rows. Retry once ruleBuilderJson answers (hub_get_rule_health lists the read failure under checkErrors), or use replaceActions to rebuild the action list atomically. RM is not touched.")
        }
        def currentIssues = _rmStructuralIssuesFromSequence(
            _rmStructuralSequenceFromSettings(settingsByName, ([] as Set), orderedIndices))
        def projectedIssues = _rmStructuralIssuesFromSequence(
            _rmStructuralSequenceFromSettings(settingsByName, ([actionIdx] as Set), orderedIndices))
        def newIssues = projectedIssues - currentIssues
        if (newIssues) {
            throw new IllegalArgumentException("removeAction(${actionIdx}) blocked: action ${actionIdx} is a structural ${humanKind}; removing it would introduce a new structural-balance issue (${newIssues.first()}). Remove the matching opener/closer first, or use replaceActions to rebuild the action list atomically. RM is not touched.")
        }
    }
    _rmClickAppButton(appId, actionIdx.toString(), "delAct", "selectActions")
    // Verify the action actually disappeared. RM 5.1 silently no-ops the
    // delAct click if the button-handler dispatch races with another edit.
    // state.editAct was clear at pre-flight, so the loop below absorbs the
    // two failure modes a clean 200 on the click can hide:
    //   (a) propagation lag -- the click committed but appSettings updates
    //       asynchronously (observed at several seconds in live repros);
    //   (b) a silently DROPPED first click -- RM 5.1 occasionally no-ops the
    //       first delAct entirely (live-measured 2/2 on a quiet hub,
    //       2026-06-12), so nothing ever propagates.
    // The old loop (1s/3s/6s backoff) treated both the same: it burned the
    // whole ~10s budget, pushed the op past the ~10s cloud-relay ceiling
    // (10.4s direct-timed on a QUIET hub, so every cloud caller got a 504),
    // and then threw -- while its own error text prescribed the verified
    // re-click performed below. Now: short early polls absorb (a); if the
    // index is STILL present at ~2.5s, that is the dropped-click signature
    // (b) -- re-click ONCE (the immediately-preceding presence check is the
    // verified-non-commit evidence that makes the second click duplicate-
    // safe) and keep polling. Success lands ~4-7s even on a dropped first
    // click, back under the relay ceiling.
    def afterIndices = null
    def reclicked = false
    def retryDelaysMs = [1000, 1500, 2000, 3000]
    for (int attempt = 0; attempt < 5; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        // Settings, like beforeIndices above: polling the compiled list instead would report
        // success the instant a row that was never in it is asked about -- a leftover row would
        // read as "already deleted" before the click landed. But the LIVE view (a row whose
        // actType/actSubType still carries a VALUE), because RM's UI leaves the emptied keys
        // behind on some firmware: keyed on presence alone a landed delete reads as still-present,
        // and this loop re-clicks a deleted row and then reports a failure that did not happen.
        afterIndices = _rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId))
        if (!afterIndices.contains(actionIdx)) {
            def out = [success: true, removedIndex: actionIdx, beforeIndices: beforeIndices.sort(), afterIndices: afterIndices.sort()]
            if (reclicked) out.reclicked = true
            return out
        }
        if (!reclicked && attempt == 2) {
            reclicked = true
            _rmClickAppButton(appId, actionIdx.toString(), "delAct", "selectActions")
        }
    }
    throw new IllegalStateException("removeAction(${actionIdx}): after the delAct click, one verified re-click, and ~8s of polling, action ${actionIdx} is still present in rule ${appId} (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). state.editAct was clear at pre-flight and the re-click also failed to land. Recovery (verify-first, do NOT blind-retry): call hub_get_app_config(appId=${appId}) and check action ${actionIdx}. If still present -> safe to call removeAction(index:${actionIdx}) again. If gone -> a click committed late (rare extreme lag); the removal already succeeded, do not retry. Indices shift on deletion, so retrying without this check can delete the wrong action.")
}

// Delete a single trigger at a specific index. The per-row delete button on
// selectTriggers has data-stateAttribute='deleteCon' (truncated, NOT 'deleteCond'
// — verified live via embeddedActions introspection on rule 289)
// and the form-input name is the trigger index as a digit (e.g. name='1'
// for trigger 1). RM's
// appButtonHandler dispatches on the stateAttribute and uses the name to
// identify which trigger to delete.
//
// After delete, RM does NOT renumber remaining triggers -- indices are
// preserved with gaps. Subsequent addTrigger picks the next free index
// via _rmCollectTriggerIndices.
//
// Post-click verification uses a retry loop identical to _rmDeleteAction's
// to absorb RM 5.1's asynchronous button-handler dispatch. The click returns
// HTTP 200 before the deletion has propagated to appSettings. Up to 4
// attempts are made with increasing delays (1s, 3s, 6s) for a max sleep
// budget of 10s. The 10s budget covers typical and slow hub propagation;
// on retry exhaustion the throw directs the caller to verify via
// hub_get_app_config since the deletion may complete post-response.
Map _rmRemoveTrigger(Integer appId, Integer triggerIdx) {
    def beforeIndices = _rmCollectTriggerIndices(appId)
    if (!beforeIndices.contains(triggerIdx)) {
        throw new IllegalArgumentException("removeTrigger.index ${triggerIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}. RM is not touched.")
    }
    // The trigger-row Delete button on selectTriggers requires a TWO-POST
    // sequence to actually delete (verified live via Chrome XHR
    // capture against rule 290 + direct curl reproduction):
    //
    //   POST 1 — /installedapp/btn — minimal body: id, name=<idx>,
    //            stateAttribute=deleteCon, settings[<idx>]=clicked,
    //            <idx>.type=button. NO formAction/version/currentPage.
    //            This registers the deletion intent. Returns 200
    //            {"status":"success"} on its own but does NOT commit.
    //
    //   POST 2 — /installedapp/update/json — page-save with form context:
    //            formAction=update, id, version, currentPage=selectTriggers,
    //            pageBreadcrumbs=["mainPage"]. Triggers the button-handler
    //            chain that processes the registered deletion intent.
    //
    // Including the form-context fields in POST 1 (the path
    // _rmClickAppButton takes when pageName is provided) makes RM treat
    // the click as a settings-save; the deletion intent gets dropped.
    def fireDeleteSequence = {
        _rmClickAppButton(appId, triggerIdx.toString(), "deleteCon")
        // POST 2: page-save commit. Pull version from the just-fetched config
        // (cheap; we already have it locally if we want to reuse, but a fresh
        // fetch keeps the helper self-contained).
        def cfgForVersion = null
        try { cfgForVersion = _rmFetchConfigJson(appId, "selectTriggers") } catch (Exception verExc) {
            mcpLog("warn", "rm-native", "_rmRemoveTrigger: version fetch for app ${appId} failed (${verExc.message}) -- POSTing commit without version field; hub may reject with a version-conflict error on concurrent edits")
        }
        def commitBody = [
            id: appId.toString(),
            formAction: "update",
            currentPage: "selectTriggers",
            pageBreadcrumbs: _rmPageBreadcrumbs(appId, "selectTriggers", '["mainPage"]')
        ]
        if (cfgForVersion?.app?.version != null) commitBody.version = cfgForVersion.app.version.toString()
        try { hubInternalPostForm("/installedapp/update/json", commitBody) } catch (Exception postExc) {
            mcpLog("warn", "rm-native", "_rmRemoveTrigger: page-save commit failed for app ${appId} trigger ${triggerIdx} (${postExc.message}) -- the deletion may not have committed; the verify retry loop below will catch it.")
        }
    }
    fireDeleteSequence()
    // Verify the trigger actually disappeared -- same two failure modes and same
    // verified re-click recovery as _rmDeleteAction's delAct (see the comment
    // there): short early polls absorb async propagation lag; an index still
    // present at ~2.5s is the silently-DROPPED-first-click signature, so the
    // full click+commit sequence re-fires ONCE (the immediately-preceding
    // presence check is the verified-non-commit evidence that makes it
    // duplicate-safe), keeping the op under the ~10s cloud-relay ceiling.
    def afterIndices = null
    def reclicked = false
    def retryDelaysMs = [1000, 1500, 2000, 3000]
    for (int attempt = 0; attempt < 5; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        afterIndices = _rmCollectTriggerIndices(appId)
        if (!afterIndices.contains(triggerIdx)) {
            def out = [success: true, removedIndex: triggerIdx, beforeIndices: beforeIndices.sort(), afterIndices: afterIndices.sort()]
            if (reclicked) out.reclicked = true
            return out
        }
        if (!reclicked && attempt == 2) {
            reclicked = true
            fireDeleteSequence()
        }
    }
    throw new IllegalStateException("removeTrigger(${triggerIdx}): after the delete click, one verified re-click, and ~8s of polling, trigger ${triggerIdx} is still present in rule ${appId} (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). Recovery (verify-first, do NOT blind-retry): call hub_get_app_config(appId=${appId}) and check trigger ${triggerIdx}. If still present -> safe to call removeTrigger(index:${triggerIdx}) again. If gone -> a click committed late (rare); the removal already succeeded, do not retry. If a retry also fails, hub_restore_backup to the pre-operation snapshot. Indices shift on deletion, so retrying without this check can delete the wrong trigger.")
}

// Modify a single trigger's state field by opening the editCond wizard,
// writing the new tstate<N> value, and committing via hasAll.
//
// Wire format verified from RM 5.1's selectTriggers page: the per-row edit
// button has data-stateAttribute='editCond' and name=<idx>. After the click,
// the wizard exposes tstate<N> for device-state triggers (among other fields).
// The hasAll button commits the in-flight edit.
//
// Scope: only state-value changes (mods.state) are supported. Capability and
// deviceIds changes require removeTrigger + addTrigger because RM's wizard
// does not expose a capability-change path in an existing trigger slot.
Map _rmMoveAction(Integer appId, Integer actionIdx, String direction) {
    def stateAttr = direction == "up" ? "arrowUp" : (direction == "down" ? "arrowDn" : null)
    if (!stateAttr) throw new IllegalArgumentException("moveAction direction must be 'up' or 'down'")
    // Capture pre-move ORDERING from ruleBuilderJson's actionList (RM's own
    // display-ordered array). appSettings key order is arbitrary -- neither
    // display nor lexical -- so it can never serve this check; see
    // _rmCollectActionIndices. The ordered list lets us verify the action's
    // POSITION moved, not just that the index set is unchanged -- a mid-list
    // silent no-op leaves both before- and after-sets identical AND positions
    // identical, but a real move shifts position by exactly one slot.
    def beforeOrderRaw = _rmOrderedActionIndices(appId)
    if (beforeOrderRaw == null) {
        // The settings scan is not display order; a position computed from it can misclassify a
        // boundary move or verify the wrong shift, and the rule reorders silently. Refuse pre-write.
        throw new IllegalStateException("moveAction(${actionIdx}) refused: the compiled action order (ruleBuilderJson actionList) is unavailable for rule ${appId}, and positions computed from the settings scan are not display order -- a move verified against them could silently reorder the rule. Retry once ruleBuilderJson reads, or rebuild with removeAction + addAction. RM is not touched.")
    }
    if (!beforeOrderRaw.contains(actionIdx)) {
        throw new IllegalArgumentException("moveAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeOrderRaw.sort().join(', ')}")
    }
    // Pre-flight: if state.editAct is set, RM will silently no-op the move-arrow
    // click. Detect and fail immediately with a descriptive error rather than
    // burning a slow position-shift check and surfacing a confusing failure.
    // Placed after the index-existence check (mirrors _rmDeleteAction ordering):
    // an invalid index gets the more actionable IllegalArgumentException first.
    def stuckEditAct = _rmGetStateEditAct(appId)
    if (stuckEditAct != null) {
        throw new IllegalStateException("moveAction(${actionIdx}, ${direction}) blocked: rule ${appId} has state.editAct=${stuckEditAct} set from a prior interrupted action edit. RM 5.1 silently no-ops move-arrow clicks until this clears. Recovery options: (a) wait ~60s and retry (RM's stale-state timeout -- UNVERIFIED for this marker: an artificially-set editAct observed live did NOT clear after 5+ minutes); (b) open the rule in the Hubitat UI and finish or cancel the open action editor. Verified live on fw 2.5.1.135: cancelBtn/cancelAct clicks, updateRule, and an in-place hub_restore_backup do NOT clear a stuck editAct (restore-in-place rewrites settings, not app state) -- if (a) and (b) fail, delete the rule, then hub_restore_backup(scope='source', backupKey='<a recent rm-rule snapshot for this rule>', confirm=true), which RECREATES it with fresh state.")
    }
    def beforePosition = beforeOrderRaw.indexOf(actionIdx)
    def isBoundary = (direction == "up" && beforePosition == 0) ||
                     (direction == "down" && beforePosition == beforeOrderRaw.size() - 1)
    _rmClickAppButton(appId, actionIdx.toString(), stateAttr, "selectActions")
    // The compiled order ONLY: the settings fallback is arbitrary key order, and a position taken
    // from it compared against a compiled-order position can coincidentally show the expected
    // one-slot shift and report a move that never happened. Unreadable is UNCONFIRMED, and is kept
    // distinct from a genuinely empty list -- collapsing the two reported a boundary no-op as a
    // confirmed success carrying indicesAfter:[] and afterPosition:-1, i.e. "the rule has no
    // actions", for a click nobody could verify.
    def afterOrderFetched = _rmOrderedActionIndices(appId)
    def afterOrderRaw = afterOrderFetched ?: []
    def afterPosition = afterOrderRaw.indexOf(actionIdx)
    if (afterOrderFetched == null) {
        return [
            success: false,
            asyncCommitLikely: true,
            partial: true,
            index: actionIdx,
            direction: direction,
            beforePosition: beforePosition,
            indicesAfter: null,
            verifyHint: "moveAction(${actionIdx}, ${direction}) could not confirm the position shifted: the click was sent but the compiled action order (ruleBuilderJson actionList) could not be re-read afterwards. VERIFY before retrying -- read the order with hub_get_app_config(appId=${appId}); a blind retry double-shifts the action."
        ]
    }
    def cfg = null
    try { cfg = _rmFetchConfigJson(appId, "selectActions") }
    catch (Exception verifyExc) {
        mcpLog("warn", "rm-native", "moveAction: post-click selectActions fetch failed for app ${appId} (${verifyExc.message}) -- render-error check skipped, action ordering may have left the page in an inconsistent state")
    }
    def renderError = cfg?.configPage?.error
    if (renderError) {
        throw new IllegalStateException("moveAction(${actionIdx}, ${direction}): click returned 200 but selectActions render now errors: ${renderError}")
    }
    // Verify position actually shifted, except at boundaries (RM correctly
    // no-ops a move-up at position 0 or move-down at the last slot).
    if (!isBoundary) {
        def expectedShift = direction == "up" ? -1 : 1
        def actualShift = afterPosition - beforePosition
        // The move-arrow click can commit slightly AFTER the immediate re-read
        // (observed live on a slow hub: the position flips a couple seconds
        // post-click, so the first check still sees the pre-move order). Do ONE
        // short re-check to catch a fast-late commit, kept well under the cloud
        // relay's ~10s window so the call itself does not 504 (a long in-handler
        // poll would). If it STILL has not shifted, return a SOFT
        // asyncCommitLikely result (mirroring clearActions/replaceActions)
        // rather than a hard throw: a late commit reported as a hard failure is
        // a false-negative that tempts a caller into a second move that
        // double-shifts the action.
        if (actualShift != expectedShift) {
            pauseExecution(1500)
            // The re-check read is the one fetch in this otherwise verify-tolerant
            // path; if it throws (transient relay flake), treat the move as
            // unconfirmed -- fall through to the soft asyncCommitLikely return
            // rather than hard-throwing out of the function.
            try {
                afterOrderRaw = _rmOrderedActionIndices(appId) ?: []
                afterPosition = afterOrderRaw.indexOf(actionIdx)
                actualShift = afterPosition - beforePosition
            } catch (Exception recheckExc) {
                mcpLog("warn", "rm-native", "moveAction: post-pause re-check fetch failed for app ${appId} (${recheckExc.message}) -- treating as unconfirmed (soft asyncCommitLikely return)")
            }
        }
        if (actualShift != expectedShift) {
            return [
                success: false,
                asyncCommitLikely: true,
                partial: true,
                index: actionIdx,
                direction: direction,
                beforePosition: beforePosition,
                afterPosition: afterPosition,
                indicesAfter: afterOrderRaw.sort(),
                verifyHint: "moveAction(${actionIdx}, ${direction}) could not confirm the position shifted within the verify window -- the move-arrow click may have committed late, or it may have dropped. VERIFY before retrying: call hub_get_app_config(appId=${appId}) and inspect the action order. If it already changed, the move committed (do NOT retry). If unchanged, the click dropped (safe to retry). Do not blind-retry: a second move on an already-moved action shifts it the other way."
            ]
        }
    }
    return [success: true, index: actionIdx, direction: direction, beforePosition: beforePosition, afterPosition: afterPosition, indicesAfter: afterOrderRaw.sort()]
}

// Navigate to a page by firing the form-submit equivalent of the live
// UI's page transition. Hubitat's UI links (e.g. "Select Actions to Run"
// on mainPage, or the implicit return-to-parent after a sub-page wizard
// commits) submit the current page form WITH a special
// `_action_href_name|<targetPage>|<idx>` marker that tells the server
// to navigate. Verified live in Chrome network capture (2026-04-25).
//
// Used by _rmAddAction to navigate from doActPage back to selectActions
// after the actionDone click — the navigation TRIGGERS the bake of the
// in-flight action into actions[]. Without it, the action's settings
// stay in appSettings but never land in the actions[] map, and
// state.actNdx never advances.
//
// The body is intentionally minimal — the server only needs the
// navigation marker to perform the transition. We don't need to mirror
// every hidden button input on the source page.
private Map _rmNavigateToPage(Integer appId, String fromPage, String targetPage, Integer hrefIndex = 0, String hrefName = "name", Map hrefParams = null, Map cache = null, Long reqT0 = null, boolean recoverEmptyRender = false, boolean uiValidate = false) {
    // For plain page navigation use hrefName="name" + hrefIndex=0. The
    // server treats the marker `_action_href_name|<page>|0` as a generic
    // navigation request.
    //
    // For sub-page hrefs that carry params (e.g. periodic schedule has
    // params={n: 1}), the LIVE UI sends a TWO-PART marker:
    //
    //   _action_href_<linkName>|<page>|<formIndex>=clicked
    //   params_for_action_href_<linkName>|<page>|<formIndex>=<json-of-params>
    //
    // The hub reads `params_for_action_href_*` to set state.<paramKey>
    // before rendering the target page. Without it, the target page
    // renders with `Cannot get property '<paramKey>' on null object`.
    // Verified live by capturing the periodic1 button's
    // XHR body via Chrome devtools (body field count = 20, including
    // params_for_action_href_periodic1|periodic|4 = {"n":1}).
    //
    // Returns the parsed nav response. The response body is the schema
    // of the target page rendered with the param state in scope — the
    // ONLY place that schema lives, since a follow-up GET on the target
    // page won't carry the state. Callers that need the target schema
    // should consume the returned configPage rather than re-fetch.
    def actionMarker = "_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
    def body = [
        id: appId.toString(),
        formAction: "update",
        currentPage: fromPage,
        pageBreadcrumbs: _rmPageBreadcrumbs(appId, fromPage, '["mainPage"]'),
        (actionMarker): ""
    ]
    if (hrefParams != null && !hrefParams.isEmpty()) {
        def paramsMarker = "params_for_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
        body.put(paramsMarker, groovy.json.JsonOutput.toJson(hrefParams))
    }
    def fromCfg = null
    try {
        fromCfg = _rmFetchConfigJson(appId, fromPage, cache)
        def v = fromCfg?.app?.version
        if (v != null) body.version = v.toString()
    } catch (Exception versionExc) {
        mcpLog("debug", "rm-native", "_rmNavigateToPage: version fetch on ${fromPage} failed for app ${appId} (${versionExc.message}) -- sending POST without version")
    }
    // uiValidate: this navigation stands for a UI page exit or link click, which the UI refuses while
    // the page being left fails its checks. Mapped live in the RM UI (fw 2.5.1.181): leaving
    // selectTriggers after hasAll is the UI's "Done with Trigger Events" (_action_previous), leaving
    // doActPage after actionDone is the UI's own automatic _action_previous, and entering a sub-page
    // is a link click -- all validated by the UI. Internal re-renders of a page the caller is already
    // on pass false.
    if (uiValidate && fromCfg?.configPage instanceof Map) {
        def fromValues = (fromCfg.settings instanceof Map) ? (Map) fromCfg.settings : [:]
        def fromSchema = _rmCollectInputSchema(fromCfg.configPage as Map)
        _requireUiNavigationValid(appId, "leaving page '${fromPage}' for '${targetPage}'".toString(), fromSchema, fromValues)
        // The page submits an unset input's defaultValue with every navigation, so the defaults the
        // check above accepted are saved here too, as the browser would.
        def defaults = fromSchema.findAll { k, meta ->
            meta?.disabled != true && meta?.type != "button" && meta?.defaultValue != null && _uiValueIsEmpty(fromValues.get(k), meta as Map)
        }.collectEntries { k, meta -> [(k): meta.defaultValue] }
        if (defaults) {
            def defaultBody = _rmBuildSettingsBody(appId, defaults, fromSchema)
            defaultBody.remove("id")
            body.putAll(defaultBody)
        }
    }
    try {
        def resp = hubInternalPostForm("/installedapp/update/json", body)
        // This nav POST re-renders the target page + bumps app.version -> drop cached pages for
        // this app. We do NOT store the returned render: the caller consumes navResp.configPage
        // directly, and it carries state.<paramKey> context a later plain GET would not.
        _rmCacheInvalidate(cache, appId)
        if (resp?.data) {
            try {
                def navResp = new groovy.json.JsonSlurper().parseText(resp.data) as Map
                // Opt-in (the walkStep sites only): every other caller navigates to COMMIT state and
                // never reads the render, so a re-read would cost them a pause and a GET for nothing.
                return recoverEmptyRender ? _rmRecoverEmptyNavRender(appId, targetPage, hrefParams, navResp, reqT0) : navResp
            } catch (Exception parseExc) {
                mcpLog("debug", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} response wasn't JSON (${parseExc.message}) -- caller will plain-fetch the schema")
            }
        }
    } catch (Exception postExc) {
        mcpLog("warn", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} POST failed for app ${appId}: ${postExc.message} -- downstream 'X not in schema' errors likely point at this")
    }
    return null
}

private int _rmEmptyRenderPauseMs() {
    // One tuning value for one problem -- RM's transient empty render -- shared by the walker's
    // nav recovery and addAction's doActPage re-read. A method, not a field: the Groovy sandbox
    // rejects static field initializers.
    return 750
}

// RM occasionally answers a navigate with an EMPTY render -- a target page carrying no inputs and
// no hrefs -- that the very next render fills in (observed on a 2.5.1.174 hub: a doActPage with
// nothing on it, no hub error logged, the same op green on every other run). Reported as-is it
// reads as "wizard broke", and at the BEFORE site it makes the following navigate click the
// default link. The nav POST itself is state-committing (it bakes the in-flight action) and is
// not known to be idempotent, so it is never re-sent: the target page is re-READ once instead.
// Three cases are reported as-is: a page reached with href params (its schema lives only in the
// nav response, a GET cannot stand in), a spent time budget, and a re-read that is empty too.
// A null or non-JSON POST never reaches here -- that is precisely the case where the first
// request may have landed, and a retry would double-submit.
private Map _rmRecoverEmptyNavRender(Integer appId, String targetPage, Map hrefParams, Map navResp, Long reqT0) {
    if (!(navResp?.configPage instanceof Map)) return navResp
    if (hrefParams != null && !hrefParams.isEmpty()) {
        // A param-bearing sub-page's schema lives ONLY in this nav response, so a re-read cannot
        // stand in for it and the empty render is reported as-is. Say so, or it leaves no trace.
        def paramSchema = _rmCollectWalkSchema(navResp.configPage as Map, null)
        if (paramSchema.inputs.isEmpty() && paramSchema.hrefs.isEmpty()) {
            mcpLog("debug", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page, but it was entered with href params whose state a plain GET cannot reproduce; reporting it as-is")
        }
        return navResp
    }
    // A page that RENDERED AN ERROR is not a transient empty render: RM answered, and said why
    // the page has nothing on it (live: a doActPage entered without the wizard state RM expects
    // answers "Cannot invoke method startsWith() on null object" on the POST and on a GET alike).
    // Re-reading it yields the same error; the walker surfaces the error text instead.
    if (navResp.configPage.error != null) return navResp
    def schema = _rmCollectWalkSchema(navResp.configPage as Map, null)
    if (!schema.inputs.isEmpty() || !schema.hrefs.isEmpty()) return navResp
    if (_timeBudgetExceeded(reqT0)) {
        mcpLog("warn", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page; time budget spent, reporting it as-is")
        return navResp
    }
    mcpLog("warn", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page; re-reading it once")
    pauseExecution(_rmEmptyRenderPauseMs())
    try {
        def reread = _rmFetchConfigJson(appId, targetPage)
        if (reread?.configPage instanceof Map) {
            def again = _rmCollectWalkSchema(reread.configPage as Map, null)
            if (!again.inputs.isEmpty() || !again.hrefs.isEmpty()) {
                // The re-read's `app` token travels with its configPage: leaving the POST's stale
                // one beside a fresh page hands the next form post an out-of-date app.version.
                def grafted = [configPage: reread.configPage, navRetried: true]
                if (reread.app != null) grafted.app = reread.app
                return navResp + grafted
            }
        }
    } catch (Exception rereadExc) {
        mcpLog("debug", "rm-native", "navigate -> ${targetPage} re-read failed for app ${appId}: ${rereadExc.message}")
    }
    return navResp
}

// Submit a sub-page back to its parent via _action_previous=Done, carrying
// ALL the page's current setting values + sidecar fields. The form-encoded
// Done is what bakes the trigger/action description into the parent's row;
// forward-nav markers (_action_href_*) reach the parent visually but leave
// the row unrendered ("?").
//
// Live-captured 2026-04-25 from Hubitat's Periodic Schedule "Done" XHR
// (firmware 2.5.0.123): the body carries settings[X] values for every
// input on the page + per-type sidecars (X.type, X.multiple,
// checkbox[X]=on for bools, hours/minutes/amPm[X] for times) +
// paramsForPage:{n:1} + pageBreadcrumbs:[mainPage,parent].
//
// Used by both walkStep's "done" op and the high-level addTrigger flow
// for sub-page-driven capabilities (Periodic Schedule, Cron String,
// etc.). Caller passes the current page name + parent page + the href
// params (so paramsForPage routes correctly).
private void _rmSubmitSubPageDone(Integer appId, String page, String parentPage, String hrefName, Map hrefParams, Map cache = null, Map renderedCfg = null) {
    // Sub-pages with route params (periodic.n, etc.) lose state.<paramKey>
    // on a plain GET — `_rmFetchConfigJson(appId, page)` returns the page
    // rendered with state=null, which means the schema is empty/wrong and
    // the version field is unreadable. Round-trip via _rmNavigateToPage to
    // get a fresh response that has the param state in scope, unless the
    // caller already holds that render (renderedCfg, with its app version).
    def cfg = renderedCfg
    if (!(cfg?.configPage instanceof Map) || cfg?.app?.version == null) {
        def hrefIndex = hrefParams?.n != null ? (hrefParams.n as Integer) : 0
        def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName ?: "name", hrefParams, cache)
        cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page, cache)
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def status = _rmFetchStatusJson(appId)
    def liveSettings = _rmLiveSettingsFromStatus(status)
    def settingsMap = [:]
    schema.each { name, meta ->
        def v = _uiValueOrDefault(liveSettings.get(name), meta)
        if (v == null) v = ""
        settingsMap.put(name, v)
    }
    // The UI refuses this Done until the page passes its own checks; so does the tool.
    _requireUiNavigationValid(appId, "Done on page '${page}'".toString(), schema, settingsMap)
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = page
    body._action_previous = "Done"
    if (hrefParams != null && !hrefParams.isEmpty()) {
        body.paramsForPage = groovy.json.JsonOutput.toJson(hrefParams)
    }
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, page, parentPage ?
        groovy.json.JsonOutput.toJson(["mainPage", parentPage]) :
        '["mainPage"]')
    // Per-type sidecars the form-encoded UI emits. _rmBuildSettingsBody
    // already handles settings[X], X.type, and X.multiple (only when
    // multi=true). For Done we also need:
    //   - X.multiple=false for non-multi fields
    //   - bool: checkbox[X] = "on" (HTML checkbox marker, always sent)
    //   - time: hours[X], minutes[X], amPm[X] (empty defaults; the time
    //     value rides in settings[X] as "HH:mm")
    schema.each { name, meta ->
        def t = meta?.type?.toString()
        if (meta?.multiple != true) {
            body["${name}.multiple".toString()] = "false"
        }
        if (t == "bool") {
            body["checkbox[${name}]".toString()] = "on"
        } else if (t == "time") {
            body["hours[${name}]".toString()] = ""
            body["minutes[${name}]".toString()] = ""
            body["amPm[${name}]".toString()] = "AM"
        }
    }
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    // Let a Done-POST failure PROPAGATE (hubInternalPostForm throws on a real failure).
    // Callers handle that throw three ways, by intent -- this is why the catch is scoped at
    // each call site, not here:
    //   - addRequiredExpression STPage Done, walkStep done: UNGUARDED -- the throw bubbles to
    //     the dispatcher's backup-and-catch and surfaces as success:false (abort the op).
    //   - addTrigger periodic Done: wraps THIS call and folds the failure into skipped as a
    //     repairable partial (the trigger row already committed).
    //   - hub_set_native_app trailing Done: wraps THIS call but discards the exception
    //     (log-only) -- best-effort cleanup, the following updateRule handles the commit.
    // Catching inside the helper would erase all three distinctions, so it does not.
    hubInternalPostForm("/installedapp/update/json", body)
    // The Done POST commits the sub-page + bumps app.version -> drop cached pages for this app.
    _rmCacheInvalidate(cache, appId)
}

// Submit mainPage with `_action_update: Done` — mirrors clicking the
// "Done" button at the bottom of a classic SmartApp's mainPage. This is
// the final commit-and-exit step the live UI fires after every rule
// create/modify session. Without it the rule's session-end state can
// be incomplete (state.<...> markers from in-flight edits not cleaned
// up, subscriptions/scheduledJobs may not fully re-init).
//
// Verified live by capturing the working UI's Done click on
// a rule's mainPage: body carries _action_update=Done plus all mainPage
// input fields echoed with their type + multiple sidecars + (where
// applicable) checkbox/hours/minutes/amPm markers, plus the
// appTypeId/appTypeName/_cancellable fields the classic Done form always
// submits — the hub's update handler 500s without them on standalone
// classic apps (Button Controller-5.1, verified live on fw 2.5.0.143;
// same trio as _commitUserAppInstall). After Done, the server redirects
// to /installedapp/list?section=automations.
//
// Best-effort — even if Done fails the underlying app data is usually
// already committed by prior writes. Returns [done: true] or
// [done: false, reason: <why>] so callers can surface the miss: for
// commitButton:null app types (Basic Rule, Button Controller) the Done
// is the session's ONLY lifecycle event, so a silent miss matters there.
Map _rmSubmitMainPageDone(Integer appId) {
    // Most app types commit on mainPage -- fetch it directly so the common path stays a SINGLE config
    // render (no per-edit regression). Button Rule-5.1's commit page is 'selectActions' instead, so a
    // mainPage fetch fails there ("Cannot find page 'mainPage'"); ONLY on that miss do we probe the app
    // type from the root config and retry on the resolved page (mirrors _rmInitSelectActionsPage).
    def commitPage = "mainPage"
    def cfg
    try {
        cfg = _rmFetchConfigJson(appId, commitPage)
    } catch (Exception mainExc) {
        String resolved = null
        try {
            def rootCfg = _rmFetchConfigJson(appId)
            if (rootCfg?.app?.appType?.name == "Button Rule-5.1") resolved = "selectActions"
        } catch (Exception probeExc) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: page-graph probe failed for app ${appId} after a mainPage miss (${probeExc.message})")
        }
        if (resolved == null) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: mainPage fetch failed for app ${appId} (${mainExc.message}) -- skipping Done click; lingering state markers (state.editAct/state.editCond) may corrupt subsequent edits")
            return [done: false, reason: "mainPage fetch failed: ${mainExc.message}".toString()]
        }
        commitPage = resolved
        try { cfg = _rmFetchConfigJson(appId, commitPage) }
        catch (Exception reExc) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: ${commitPage} fetch failed for app ${appId} (${reExc.message}) -- skipping Done click")
            return [done: false, reason: "${commitPage} fetch failed: ${reExc.message}".toString()]
        }
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def status
    try { status = _rmFetchStatusJson(appId) }
    catch (Exception statusExc) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: statusJson fetch failed for app ${appId} (${statusExc.message}) -- skipping Done click rather than re-submitting the page blind")
        return [done: false, reason: "statusJson fetch failed: ${statusExc.message}".toString()]
    }
    // Anomalous-shape guard: if statusJson parses but carries no appSettings
    // LIST, rebuilding the form would submit EVERY input as "" -- the
    // all-fields variant of the capability wipe _rmLiveSettingsFromStatus
    // exists to prevent. Skip the Done instead. (A legitimately EMPTY list
    // on a fresh shell still proceeds -- there is nothing to wipe.)
    if (!(status?.appSettings instanceof List)) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: statusJson for app ${appId} has no appSettings list -- skipping Done click rather than blanking ${schema.size()} inputs")
        return [done: false, reason: "statusJson carried no appSettings list"]
    }
    def liveSettings = _rmLiveSettingsFromStatus(status)
    def settingsMap = [:]
    schema.each { name, meta ->
        def v = _uiValueOrDefault(liveSettings.get(name), meta)
        if (v == null) v = ""
        settingsMap.put(name, v)
    }
    try {
        _requireUiNavigationValid(appId, "Done on page '${commitPage}'".toString(), schema, settingsMap)
    } catch (IllegalStateException uiBlocked) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: ${uiBlocked.message}")
        return [done: false, reason: uiBlocked.message, uiBlocked: true]
    }
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = commitPage
    body._action_update = "Done"
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, commitPage, "[]")
    schema.each { name, meta ->
        def t = meta?.type?.toString()
        if (meta?.multiple != true) {
            body["${name}.multiple".toString()] = "false"
        }
        if (t == "bool") {
            body["checkbox[${name}]".toString()] = "on"
        } else if (t == "time") {
            body["hours[${name}]".toString()] = ""
            body["minutes[${name}]".toString()] = ""
            body["amPm[${name}]".toString()] = "AM"
        }
    }
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    // The classic Done form always submits these; without them the hub's
    // update handler 500s on standalone classic apps (verified live on
    // Button Controller-5.1 -- the 500 was previously swallowed here).
    body.appTypeId = ""
    body.appTypeName = ""
    body._cancellable = "false"
    def resp
    try { resp = hubInternalPostForm("/installedapp/update/json", body) } catch (Exception e) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: final Done POST for app ${appId} failed (${e.message}) -- state.editAct / state.editCond markers from in-flight edits may not have been cleared; next edit session should verify via hub_get_app_config")
        return [done: false, reason: "Done POST failed: ${e.message}".toString()]
    }
    if (resp?.status != null && resp.status >= 400) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: Done POST for app ${appId} returned status ${resp.status}")
        return [done: false, reason: "Done POST returned status ${resp.status}".toString()]
    }
    return [done: true]
}

private Map _rmWriteSubPageField(Integer appId, String page, String parentPage, String hrefName, Integer hrefIndex, Map hrefParams, String key, Object value, Map cache = null) {
    // For pages with meaningful state.<paramKey> (e.g. periodic schedule's
    // state.n), schema requires the navigate response to set state in scope.
    // For pages with placeholder hrefParams (STPage uses [unUsed: null]),
    // re-firing the action_href on each write RESETS RM's in-flight wizard
    // accumulator (cond-builder, etc.) and breaks multi-step flows that
    // use sub-expressions. Detect "no real params" via all-null values
    // and use a plain GET in that case.
    def hasRealParams = (hrefParams != null) && hrefParams.values().any { it != null }
    def cfg
    if (hasRealParams) {
        def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams, cache)
        cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page, cache)
    } else {
        cfg = _rmFetchConfigJson(appId, page, cache)
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def beforeKeys = (schema?.keySet() ?: []) as Set
    def beforeValueStr = schema?."${key}"?.value?.toString()
    def beforeRenderHash = _rmSanitizeRenderForHash(cfg?.configPage?.sections).hashCode()
    def body = _rmBuildSettingsBody(appId, [(key): value], schema)
    // `cond` and `oper` are the condition-builder's enum pickers, and their type is known a priori.
    // The request-scoped page cache consumes each write's inline echo, but a submitOnChange echo for
    // these pickers lags one render behind -- a gap-operator (and the close-sub-expression operator)
    // echoes a page that omits the very field the NEXT write needs. If the next write reads that
    // cached schema, _rmBuildSettingsBody finds no `cond`/`oper` entry and DROPS the `<key>.type`
    // sidecar, which RM silently no-ops -- the multi-condition / sub-expression RE failure
    // ("rCapab_<N> not in STPage schema after cond=a"). Emit the picker's enum type explicitly so the
    // write lands against the hub's real (settled) page regardless of the cached schema -- no
    // invalidation, no extra re-fetch (keeps the round-trip win). Live-root-caused 2026-06-21 via the
    // native RM wizard + claude-in-chrome.
    if (key == "cond" || key == "oper") {
        body["${key}.type".toString()] = "enum"
        body["${key}.multiple".toString()] = "false"
    }
    body.formAction = "update"
    body.currentPage = page
    // Live-UI capture (Chrome 2026-04-26, rule 1380 STPage cond=b probe):
    // when writing a setting on a sub-page that's already navigated to,
    // pageBreadcrumbs is `[]` (empty) and the body carries NO action_href
    // markers — those are exclusively for the navigation POST. Including
    // `_action_href_*` in the write re-fires the navigation handler, which
    // resets in-flight wizard state (state.<paramKey>, condition-builder
    // accumulators) and corrupts subsequent renders. Periodic schedule writes keep state.n
    // alive via paramsForPage on the Done back-nav (see _rmSubmitSubPageDone),
    // not via re-firing the action_href on every write.
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, page, '[]')
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    def postResp = hubInternalPostForm("/installedapp/update/json", body)
    // Post-write verification. The classic dynamicPage submit RETURNS the re-rendered page model
    // inline (appUI.js jsonSubmit consumes data.configPage with NO follow-up GET), so on the
    // plain-params path (STPage/doActPage condition writes — the hot path) we consume that
    // response as afterCfg, eliminating the separate verify-GET. The hasRealParams path keeps its
    // re-navigate so the state.<paramKey> render context (periodic schedule's state.n) is
    // preserved exactly. Either way afterCfg drives the same schema/value/render diff below
    // (applied vs silently-rejected). RM 5.1 returns 200 for many writes that never land, so the
    // diff — not the POST status — is what routes applied vs skipped.
    def afterCfg = null
    def verifyFetchErr = null
    def parsedPost = hasRealParams ? null : _rmPagePostResponse(postResp)
    if (parsedPost != null) {
        // Consume the inline echo as both the post-write state (routing below) AND the cached page
        // for the next write -- no verify-GET, no invalidation. The condition-builder's `cond`/`oper`
        // picker echoes can lag a render behind (omitting the next field), but the explicit
        // `<key>.type` sidecar above makes the next picker write land against the hub's real page
        // regardless, so caching the lagged echo is safe and keeps the round-trip count minimal.
        _rmCacheStore(cache, appId, page, parsedPost)
        afterCfg = parsedPost
    } else {
        _rmCacheInvalidate(cache, appId)
        try {
            afterCfg = hasRealParams ? _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams, cache) : _rmFetchConfigJson(appId, page, cache)
        } catch (Exception fetchExc) {
            verifyFetchErr = fetchExc.message
            mcpLog("warn", "rm-native", "_rmWriteSubPageField: post-write fetch on ${page} failed for app ${appId} key=${key} (${fetchExc.message}) -- write status is unverified")
        }
    }
    if (afterCfg == null) {
        // Verification fetch failed OR returned null. We CANNOT confirm
        // persistence — return persisted=false with verifyFetchFailed=true
        // so the caller can route this into a distinct skipped bucket
        // ("we don't know if the write landed") rather than falsely
        // declaring it applied (the comparison-against-empty-schema
        // would otherwise produce schemaShifted=true and route to
        // applied without verification).
        return [persisted: false, schemaShifted: false, valueLanded: false, renderShifted: false, verifyFetchFailed: true, verifyError: verifyFetchErr, afterKeys: []]
    }
    def afterSchema = _rmCollectInputSchema(afterCfg?.configPage)
    def afterKeys = (afterSchema?.keySet() ?: []) as Set
    def afterValueStr = afterSchema?."${key}"?.value?.toString()
    def afterRenderHash = _rmSanitizeRenderForHash(afterCfg?.configPage?.sections).hashCode()
    // Stringify list values deterministically so idempotent List writes
    // (already-set multi-enum re-applied) match cleanly via valueLanded.
    // Without this, value instanceof List ⇒ newValueStr=null ⇒ valueLanded=false,
    // and a no-op re-write of a stable list would wrongly route to skipped.
    def newValueStr
    if (value instanceof List) {
        newValueStr = ((value as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else {
        newValueStr = value?.toString()
    }
    def afterValueNorm = afterValueStr
    if (afterValueStr && value instanceof List && afterValueStr.contains(",")) {
        afterValueNorm = afterValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
    }
    def schemaShifted = (beforeKeys != afterKeys) || (beforeValueStr != afterValueStr)
    def valueLanded = (newValueStr != null) && (afterValueNorm == newValueStr)
    // Wizard-consumed: many sub-page enum pickers (STPage cond, doActPage cond,
    // STPage oper) have the field reset to empty after the wizard advances —
    // before/after schema and field value look identical (both empty), but
    // RM's rendered paragraph text DID shift to reflect the new wizard state.
    // renderShifted catches that case.
    //
    // MASKING CAVEAT: renderShifted is a coarse signal -- it credits `persisted`
    // on ANY post-sanitization paragraph delta, not just one caused by THIS field.
    // For a genuinely-rejected write into a hidden field (e.g. Site B's
    // neither-rendered comparator), an UNRELATED paragraph shift from a sibling
    // field can flip renderShifted true and mask the rejection as `applied` instead
    // of `silent_rejection`. renderShifted stays in `persisted` because it is the
    // only signal for wizard-consumed enum pickers (cond/oper) where valueLanded
    // and settingsLanded cannot fire; the trade-off is a known false-positive on
    // the hidden-field edge. Callers that need a true value-landed guarantee should
    // inspect the discrete valueLanded / settingsLanded flags in the return Map.
    def renderShifted = (beforeRenderHash != afterRenderHash)
    // settingsLanded: catches wizard-consumed fields (RelrDev_N, useLastDev.N,
    // time1, etc.) that disappear from configPage schema after the wizard
    // advances but persist in settings. Applies only on STPage path where
    // afterCfg is a full /installedapp/configure/json response (has settings).
    // On the periodic schedule path, afterCfg is the navigate response shape
    // [configPage:, app:] with no settings key, so settingsValue is null and
    // settingsLanded stays false -- no over-promotion risk.
    //
    // Same normalization strategy as valueLanded: List values are sorted and
    // comma-joined; scalar strings are compared directly. Mirrors the identical
    // fix applied to _rmWriteSettingOnPage for the same field class.
    def settingsValue = afterCfg?.settings?."${key}"
    def settingsValueNorm
    if (settingsValue instanceof List) {
        settingsValueNorm = ((settingsValue as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else if (settingsValue != null) {
        def settingsValueStr = settingsValue.toString()
        if (value instanceof List && settingsValueStr.contains(",")) {
            settingsValueNorm = settingsValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
        } else {
            settingsValueNorm = settingsValueStr
        }
    }
    def settingsLanded = (newValueStr != null) && (settingsValueNorm != null) && (settingsValueNorm == newValueStr)
    def persisted = schemaShifted || valueLanded || renderShifted || settingsLanded
    return [persisted: persisted, schemaShifted: schemaShifted, valueLanded: valueLanded, renderShifted: renderShifted, settingsLanded: settingsLanded, verifyFetchFailed: false, afterKeys: afterKeys.toList().sort()]
}

// Resolve a collection of mode keys (Integer IDs OR String names like "Day"/
// "Away") into a List of mode IDs as Strings, in insertion order. Used by
// per-mode action subtypes to write the modes list field. The hub's mode
// IDs come from location.modes.
// A mode multi-select is a LIST: RM stores one mode ID per element, so a single
// comma-joined string ("Day,Evening") is looked up as one nonexistent mode. When a
// name fails to resolve AND contains a comma, steer to the list shape rather than the
// opaque "unknown mode" so the caller stops comma-joining. Returns the hint message,
// or null when the value is not a comma-joined string (caller falls through to its
// normal unknown-mode error). Only consulted on the not-found path, so a real
// comma-bearing mode name that DID resolve is never second-guessed. The body carries NO
// surface word -- each caller passes its own `context` (e.g. "addTrigger Mode",
// "conditions[2]") so the message is prefixed once, never doubled. ASCII-only.
private String _rmRetentionSuffix(Map backup) {
    return backup?.retentionNote ? " ${backup.retentionNote}".toString() : ""
}

// The roll-back hint for a write that may have changed the rule.
private boolean _rmIsCommittedRETell(Set names, boolean requireStopOnST = false) {
    if (names == null) return false
    def hasNewCondSelector = names.contains("cond") || names.any { it.startsWith("rCapab_") }
    def base = names.contains("cancelST") && names.contains("editST")
    if (requireStopOnST) base = base && names.contains("stopOnST")
    return base && !hasNewCondSelector
}

// Strip a configPage's schema down to the fields an LLM caller actually
// needs to drive the wizard. Drops sandbox-blocked HTML, layout hints,
// and other noise that bloats responses without adding decision-making
// value.
//
// Returns a list of {name, type, title, multiple, required, options,
// currentValue} for each input the page exposes, plus {hrefs} for any
// navigation links pointing at sub-pages.
private Map _rmCollectWalkSchema(Map configPage, Map liveSettings = null) {
    def inputs = []
    def hrefs = []
    def hasActionDone = false
    def hasHasAll = false
    def hasCancel = false
    for (s in (configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (!(i instanceof Map) || !i.name) continue
            def name = i.name.toString()
            def entry = [
                name: name,
                type: i.type?.toString(),
                title: (i.title?.toString() ?: "").take(80),
                multiple: i.multiple == true,
                required: i.required == true
            ]
            if (i.options != null) {
                // Enum options can be list of strings or list of single-key maps.
                // Surface as a flat list of {value, label} pairs for clarity.
                def opts = []
                (i.options as List).each { o ->
                    if (o instanceof Map && !o.isEmpty()) {
                        def k = o.keySet().iterator().next()
                        opts << [value: k.toString(), label: o.get(k)?.toString()]
                    } else {
                        opts << [value: o?.toString(), label: o?.toString()]
                    }
                }
                entry.options = opts
            }
            if (liveSettings != null && liveSettings.containsKey(name)) {
                entry.currentValue = liveSettings[name]
            }
            inputs << entry
            if (i.type == "button") {
                if (name == "actionDone") hasActionDone = true
                if (name == "hasAll") hasHasAll = true
                if (name?.toLowerCase()?.contains("cancel")) hasCancel = true
            }
        }
        for (b in (s?.body ?: [])) {
            if (b instanceof Map && b.element == "href" && b.page) {
                hrefs << [
                    name: b.name?.toString(),
                    title: b.title?.toString(),
                    targetPage: b.page?.toString(),
                    params: b.params
                ]
            }
        }
    }
    return [
        inputs: inputs,
        hrefs: hrefs,
        commitButtons: [
            actionDone: hasActionDone,
            hasAll: hasHasAll,
            cancel: hasCancel
        ]
    ]
}

// walkStep — single-step wizard introspection + write/click/navigate.
//
// Designed for LLM-driven dynamic wizard walking. Each call performs at
// most ONE operation and returns a structured snapshot of how the page
// changed: schema diff (inputs that appeared/disappeared/changed), the
// stored-value echo for any write (catches enum case normalization +
// coercion that the schema wouldn't reveal), sub-page hrefs with their
// target page, list-count change for actions/triggers (disambiguates
// "wizard committed cleanly" from "schema went empty because the rule
// is broken"), and a full health check.
//
// Spec shape:
// { page: <pageName>,
// operation: "introspect" | "write" | "click" | "navigate" | "done",
// write?: {<key>: <value>},
// click?: {name, stateAttribute?},
// navigate?: {targetPage},
// validateEnum?: bool,  // if true, reject writes whose value
// // isn't in the input's options list
// hrefContext?: {fromPage, hrefName, hrefParams?, hrefIndex?}
// // for sub-page ops + back-nav (done)
// }
//
// "done" submits the current sub-page back to its parent via
// _action_previous=Done, carrying ALL current setting values. Required
// for sub-pages whose parent's row description only renders after a
// full Done submit (e.g. Periodic Schedule renders "?" without it).
// Pass hrefContext={fromPage: <parent>, hrefParams: {n: <idx>}} so RM
// routes the back-nav with the correct paramsForPage.
//
// For "introspect" the call only fetches the schema — no mutation. The
// `before` snapshot is the same as `after` and `diff` is empty.
private def _rmRecentRuleBackup(Map mfst, Integer ruleId, long nowMs) {
    return mfst.findAll { key, value ->
        if (!(value instanceof Map) || value.deletePending || value.type?.toString() != "rm-rule") return false
        def savedRuleId = value.ruleId != null ? value.ruleId : value.id
        if (savedRuleId?.toString() != ruleId?.toString()) return false
        Long savedAt = null
        try { savedAt = value.timestamp as Long } catch (Exception ignored) { }
        if (savedAt == null) return false
        long age = nowMs - savedAt
        return age >= 0L && age < 60L * 60L * 1000L
    }.max { a, b -> (a.value.timestamp as Long) <=> (b.value.timestamp as Long) }
}

private String _rmCaptureRuleExport(Integer ruleId) {
    def raw = _nrHandle(ruleId.toLong()).exportAppData([ruleId.toLong()])
    _rmDecodeRuleExport(ruleId, raw)
    raw
}

private Map _rmDecodeRuleExport(Integer ruleId, Object raw) {
    if (!(raw instanceof String) || !raw) throw new IllegalStateException('Native recovery export is unavailable')
    def payload = new groovy.json.JsonSlurper().parseText(raw)
    def identity = payload instanceof Map && payload.appReplacements instanceof Map ? payload.appReplacements.find { key, value -> key.toString() == ruleId.toString() }?.value : null
    def data = payload instanceof Map && payload.appData instanceof Map ? payload.appData.find { key, value -> key.toString() == ruleId.toString() }?.value : null
    if (!(identity instanceof Map) || identity.appTypeNamespace != 'hubitat' || identity.appTypeName != 'Rule-5.1' ||
        !identity.parentAppInstalledAppId?.toString()?.isLong() || identity.parentAppInstalledAppId.toString().toLong() <= 0L ||
        !(payload.deviceReplacements instanceof Map) || !(data instanceof Map) || !(data.state instanceof Map) ||
        ['actions', 'capabstrue', 'capabsfalse', 'eval'].any { data.state.get(it) != null && !(data.state.get(it) instanceof Map) } ||
        (data.state.actionList != null && !(data.state.actionList instanceof List)) ||
        data.state.keySet().any { !(it instanceof String) || !it } ||
        ['stopped', 'paused'].any { data.state.containsKey(it) && !(data.state.get(it) instanceof Boolean) } ||
        !(data.appSettings instanceof List) || !(data.subscriptions instanceof List) ||
        data.appSettings.any { !(it instanceof Map) || !(it.name instanceof String) || !it.name || !(it.type instanceof String) || !it.type } ||
        data.appSettings.collect { it.name }.unique().size() != data.appSettings.size()) {
        throw new IllegalStateException('Native recovery export does not identify a complete Rule-5.1 configuration')
    }
    _rmNativeSettingPlan(data.appSettings)
    payload
}

Map _rmBackupRuleSnapshot(Integer ruleId, String reason, boolean requireNativeExport = false, String protectedKey = null) {
    // Strict edit capture never renders the editor before its recovery point is saved.
    def built = requireNativeExport ? _rmBuildNativeEditSnapshot(ruleId, reason, _rmCaptureRuleExport(ruleId)) : _rmBuildRuleSnapshot(ruleId, reason)
    return _withBackupLock("rule ${ruleId} snapshot (${reason})") { _rmWriteRuleSnapshotLocked(ruleId, reason, built.snapshot as Map, built.config as Map, protectedKey) }
}

private Map _rmBuildNativeEditSnapshot(Integer ruleId, String reason, String nativeExport) {
    Map payload = new groovy.json.JsonSlurper().parseText(nativeExport) as Map
    Map identity = payload.appReplacements.find { key, value -> key.toString() == ruleId.toString() }.value as Map
    Map data = payload.appData.find { key, value -> key.toString() == ruleId.toString() }.value as Map
    Map savedSettings = [:]
    List settingRows = data.appSettings.collect { row ->
        savedSettings.put(row.name, row.deviceList instanceof Map && row.deviceList ? row.deviceList : row.value)
        [:] + row + [deviceIdsForDeviceList: row.deviceList instanceof Map ? row.deviceList.keySet().collect { it.toString() } : []]
    }
    Map config = [app: [id: ruleId, label: identity.appLabel, trueLabel: identity.appLabel,
        appType: [name: identity.appTypeName, namespace: identity.appTypeNamespace]], settings: savedSettings,
        configPage: [name: 'mainPage', sections: [[input: settingRows.collect { row -> [element: 'input', name: row.name, type: row.type, multiple: row.multiple == true] }]]]]
    long capturedAt = now()
    Map snapshot = [schemaVersion: 1, captureFormat: 'native-rule-edit-v1', ruleId: ruleId, appId: ruleId, appType: 'rule_machine', reason: reason,
        timestamp: capturedAt, timestampIso: formatTimestamp(capturedAt), appLabel: stripAppConfigHtml(identity.appLabel),
        nativeExport: nativeExport, configJson: config,
        statusJson: [appSettings: settingRows, appState: data.state.collect { key, value -> [name: key, value: value] }]]
    [snapshot: snapshot, config: config]
}

private Map _rmBuildRuleSnapshot(Integer ruleId, String reason) {
    def config
    def status
    try {
        config = _rmFetchConfigJson(ruleId)
    } catch (Exception e) {
        // Vue-JSON children (Visual Rules) may not serve configure/json. If the id speaks a
        // VRB serialization, the VRB-flavored snapshot below is still a full backup --
        // restore replays the captured definition, not classic settings.
        def vrbFallback = null
        try { vrbFallback = _vrbDetect(ruleId) } catch (Exception ignored) { }
        if (vrbFallback == null) {
            if (e.message?.contains("404")) {
                throw new IllegalArgumentException("No rule/app with id ${ruleId} exists on this hub (configure/json returned 404). Nothing was changed. Use hub_list_rules for valid ids. See hub_get_tool_guide(section='set_rule_reference').")
            }
            throw new IllegalArgumentException("Cannot back up rule ${ruleId}: configure/json failed -- ${e.message}")
        }
        config = null
    }
    try {
        status = _rmFetchStatusJson(ruleId)
    } catch (Exception e) {
        // Status failure is tolerable for a pre-write snapshot — the
        // config JSON alone is enough to restore. Record the failure in
        // the snapshot so post-mortem sees why status was absent.
        mcpLog("warn", "rm-native", "Backup for rule ${ruleId}: statusJson failed -- ${e.message}")
        status = [error: e.message ?: e.toString()]
    }

    // Detect appType from the config's appType.name so the restore path
    // can route to the right registry entry. RM 5.1 = "rule_machine";
    // future appTypes get reverse-mapped from the registry.
    def detectedAppType = config == null ? "visual_rule" : "rule_machine"
    def configAppName = config?.app?.appType?.name
    if (configAppName) {
        // The hub reports Visual Rule children VERSIONED ("Visual Rule Builder 1.0" / "... 2.0")
        // while the registry holds the bare family name, so an exact-only lookup silently
        // classified every Visual Rule as rule_machine -- the snapshot then skipped the definition
        // capture and a restore replayed it as an RM rule. Match the name with a trailing version
        // stripped as well; requiring the suffix to LOOK like a version keeps "Visual Rule
        // Builder" from swallowing the "Visual Rules Builder" parent or any unrelated app.
        def name = configAppName.toString()
        def versioned = (name =~ /^(.+) [0-9]+(?:\.[0-9]+)*$/)
        def bare = versioned.matches() ? (versioned[0] as List)[1].toString() : null
        // Exact match outranks the stripped one, and the first hit wins -- a `.each` that kept
        // assigning would let the last registry entry override an earlier exact match.
        def exactKey = _appTypeRegistry().find { typeKey, reg -> reg.appName == name }?.key
        def bareKey = (exactKey == null && bare != null) ? _appTypeRegistry().find { typeKey, reg -> reg.appName == bare }?.key : null
        detectedAppType = exactKey ?: bareKey ?: detectedAppType
    }
    // The hub's appType record carries the app type's OAuth client credentials and passwords
    // (populated for Rule-5.1); restore never reads them, so they stay out of File Manager.
    if (config?.app?.appType instanceof Map) {
        ["oauthClientId", "oauthClientSecret", "encryptedPassword", "sourcePassword"].each { config.app.appType.remove(it) }
    }

    def snapshot = [
        schemaVersion: 1,
        ruleId: ruleId,           // legacy field; new snapshots also carry appId
        appId: ruleId,
        appType: detectedAppType,
        reason: reason ?: "pre-write",
        timestamp: now(),
        timestampIso: formatTimestamp(now()),
        appLabel: stripAppConfigHtml(config?.app?.trueLabel ?: config?.app?.label),
        configJson: config,
        statusJson: status
    ]

    // Visual Rules keep their definition in app state behind the ruleBuilder endpoints, not
    // in classic settings -- capture it so _vrbRestoreFromSnapshot can replay it. A null
    // detect (unreadable / never-saved shell) is recorded as a husk; restore then fails
    // with an actionable error instead of silently recreating an empty rule.
    if (detectedAppType == "visual_rule") {
        def vrb = null
        try {
            vrb = _vrbDetect(ruleId)
            if (vrb == null) {
                mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: no readable definition (never-saved shell?) -- snapshot is a husk; restore will refuse it")
            }
        } catch (Exception e) {
            mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: definition capture failed -- ${e.message}")
        }
        if (vrb != null) {
            snapshot.vrbFormat = vrb.format
            snapshot.vrbRulePaused = vrb.data.rulePaused == true
            // Prefer the rule's OWN name over the installed-app label: the hub decorates a
            // paused rule's label with " (Paused)" (live-verified), and a restore that
            // replays the decorated label bakes the suffix into the real rule name.
            if (vrb.data.name?.toString()?.trim()) snapshot.appLabel = vrb.data.name
            if (vrb.format == "classic") {
                snapshot.vrbDefinition = [whenNodes: vrb.data.whenNodes ?: [],
                                          thenNodes: vrb.data.thenNodes ?: [],
                                          elseNodes: vrb.data.elseNodes ?: []]
            } else {
                // Capture the document the read RESOLVED (the hub's own loader prefers the parsed
                // graphDocument), not the raw bytes: a blank or {"nodes":[]} ruleJson beside a
                // populated graphDocument would snapshot as unreadable or as an empty graph, and a
                // restore would then replay the empty graph and verify zero against zero. The key
                // keeps its name so existing backups still restore.
                def doc = vrb.data.definition
                // A rollback handle the reader ALREADY KNOWS is unreadable is worse than none: the
                // file uploads, the manifest registers it, the delete envelope advertises it, and
                // the restore that follows the delete answers "not parseable JSON" with the rule
                // already gone. definitionParseError is exactly that knowledge, so refuse here --
                // the caller gates its destructive op on this backup, and refusing the backup
                // refuses the delete.
                if (doc == null && vrb.data.definitionParseError) {
                    throw new IllegalArgumentException(
                            "Cannot back up Visual Rule ${ruleId}: its stored graph document is unreadable " +
                            "(${vrb.data.definitionParseError}), so no restorable snapshot can be taken and " +
                            "nothing was written. Inspect it with hub_get_visual_rule(appId=${ruleId}); a rule " +
                            "whose document cannot be read can only be re-authored, not restored.")
                }
                snapshot.vrbRuleJson = (doc instanceof Map && doc.nodes instanceof List && !doc.nodes.isEmpty()) ?
                        groovy.json.JsonOutput.toJson(doc) : vrb.data.ruleJson?.toString()
                // An empty shell (no nodes, no raw bytes) is a legitimate thing to delete, so this
                // is a marker rather than a refusal -- it lets restore say the rule HAD no graph
                // instead of blaming the snapshot's age.
                if (!snapshot.vrbRuleJson) {
                    snapshot.vrbGraphEmpty = true
                    mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: the rule has no stored graph document, so the snapshot carries no definition to restore")
                }
            }
        }
    }

    // An app the registry does not know also lands on rule_machine above, so the export is gated on
    // the hub's own Rule Machine type name.
    if (detectedAppType == "rule_machine" && config != null && (configAppName?.toString() ==~ /Rule-[0-9.]+/)) {
        // Rule Machine only (restore imports it as a rule). The cloner renders no export for a rule with a
        // committed Required Expression (live, fw 2.5.2.129).
        def evalState = (status?.appState instanceof List) ? (status.appState as List).find { it instanceof Map && it.name == "eval" }?.value : null
        if (evalState instanceof Map && (evalState as Map)["0"] instanceof List && !((evalState as Map)["0"] as List).isEmpty()) {
            snapshot.nativeExportSkipped = "the rule has a Required Expression, which Hubitat's App Cloner does not export"
        } else {
            def nat = _rmNativeExportForBackup(ruleId)
            if (nat?.json) snapshot.nativeExport = nat.json
            else if (nat?.error || nat?.skipped) snapshot.nativeExportSkipped = (nat.error ?: nat.skipped).toString()
        }
    }

    return [snapshot: snapshot, config: config]
}

private Map _rmWriteRuleSnapshotLocked(Integer ruleId, String reason, Map snapshot, Map config, String protectedKey = null) {
    // Under strict per-write backups an edit inside an editing session is a rolling step. The
    // session's first snapshot, and a delete's or Required Expression replacement's recovery
    // point, stay pinned. Decided under the lock, against the committed index.
    boolean editStep = settings?.backupEveryRuleWrite == true &&
        !(reason in ["pre-delete", "pre-forcedelete", "pre-replaceRequiredExpression", "pre-native-restore"])
    String retention = editStep && _rmRecentRuleBackup(_itemBackupManifest(), ruleId, now()) != null ? "rolling" : "pinned"
    def ts = new Date(now()).format("yyyyMMdd-HHmmss-SSS")
    String namePrefix = "mcp-rm-backup-${ruleId}-"
    String nameExt = ".json"
    def fileName = _itemBackupFileName(namePrefix + ts + nameExt)

    def jsonBytes
    try {
        jsonBytes = groovy.json.JsonOutput.toJson(snapshot).getBytes("UTF-8")
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot serialize backup for rule ${ruleId}: ${e.message}")
    }
    try {
        uploadHubFile(fileName, jsonBytes)
        if (snapshot.captureFormat == 'native-rule-edit-v1') {
            def savedBytes = downloadHubFile(fileName)
            if (savedBytes == null || new String(savedBytes as byte[], 'UTF-8') != new String(jsonBytes, 'UTF-8')) throw new IllegalStateException('Uploaded recovery snapshot did not match its readback')
        }
    } catch (Exception e) {
        if (snapshot.captureFormat == 'native-rule-edit-v1') {
            try { deleteHubFile(fileName) } catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("Cannot save backup file '${fileName}' for rule ${ruleId}: ${e.message}")
    }

    def suffix = fileName.substring(namePrefix.length(), fileName.length() - nameExt.length())
    String backupKey = "rm-rule_${ruleId}_${suffix}"
    def entry = [
        type: "rm-rule",
        id: ruleId,
        ruleId: ruleId,
        fileName: fileName,
        reason: snapshot.reason,
        appLabel: snapshot.appLabel,
        timestamp: snapshot.timestamp,
        sourceLength: jsonBytes.length,  // reusing the existing field name for byte size
        sizeBytes: jsonBytes.length,
        retention: retention
    ]
    _publishUploadedItemBackup(backupKey, entry, protectedKey)

    mcpLog("info", "rm-native", "Backed up rule ${ruleId} (${reason}) to ${fileName} (${jsonBytes.length} bytes)")
    // brokenBefore: the rule's pre-write broken state, derived from the config this snapshot
    // already fetched (no extra hub read). A mutation that needs to know whether IT caused a
    // post-write break (vs the rule being broken beforehand) reads this off the returned backup to
    // steer its error wording, then STRIPS it -- it is a transient internal signal, never surfaced
    // to the caller and deliberately kept off the persisted manifest `entry` (not part of the
    // durable backup record). null when no config was readable (VRB-only snapshot).
    return [backupKey: backupKey,
            brokenBefore: (config == null ? null : _rmConfigHasBrokenMarkers(config)),
            baselineReused: false,
            rollbackScope: "Restoring this baseline returns rule ${ruleId} to its state at ${formatTimestamp(snapshot.timestamp as Long)}."] +
        entry + [retentionNote: _itemBackupRetentionNote(entry)]
}

// Soft delete via /installedapp/delete/<id>. Refuses if the app has
// child devices or child apps (hub-side safety). Returns the hub's JSON
// response verbatim so callers can surface the reason on refusal.
private Map _rmSoftDeleteApp(Integer appId) {
    def responseText = hubInternalGet("/installedapp/delete/${appId}")
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from soft-delete on app ${appId}")
    }
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        // Hub sometimes returns redirect HTML instead of JSON on soft-
        // delete success. Treat non-parseable body as success with a
        // note so callers don't see a misleading failure.
        return [success: true, raw: responseText.take(200)]
    }
    return parsed
}

// -------------------- Native RM tools (MCP-exposed) --------------------

// Local-variable reads are retained for the runtime diagnostics surface.
private Map _rmReadLocalVarsMap(Integer appId) {
    def status
    try {
        status = _rmFetchStatusJson(appId)
    } catch (Exception e) {
        return [ok: false, vars: [:], error: "${e.class.simpleName}: ${e.message ?: e.toString()}"]
    }
    def appState = status?.appState
    if (appState != null && !(appState instanceof List)) {
        // appState has always been a List-of-entries; a non-List shape would make the
        // .find below read zero locals silently. An EMPTY non-List (e.g. [:]) carries no
        // data to lose, so treat it as a plain no-locals read. A NON-EMPTY non-List shape
        // carries data we cannot interpret -- returning ok:true would mask a read failure
        // as a genuinely-empty rule, so surface it as ok:false (a read failure) instead, so
        // callers (hub_list_rule_local_variables) report a read error rather than total:0.
        if (appState instanceof Map ? !appState.isEmpty() : true) {
            def shapeLabel = (appState instanceof Map) ? "a Map" : "a non-List value"
            mcpLog("warn", "rm-native", "_rmReadLocalVarsMap: app ${appId} statusJson appState is ${shapeLabel} (expected a List of {name,value} entries) -- treating as a read failure; the allLocalVars contract may have changed.")
            return [ok: false, vars: [:], error: "statusJson appState was ${shapeLabel}, not the expected List of entries -- cannot read local variables (the allLocalVars contract may have changed)"]
        }
        return [ok: true, vars: [:]]
    }
    def raw = (appState ?: []).find { it?.name?.toString() == "allLocalVars" }?.value
    // A running rule updates lv_<name>; allLocalVars only catches up when the rule's page renders.
    def live = [:]
    (appState ?: []).each { e ->
        def n = e?.name?.toString()
        // Local names can be sandbox property names such as fields; use Map.put for arbitrary keys.
        if (n?.startsWith("lv_") && e.value instanceof Map) live.put(n.substring(3), e.value)
    }
    return [ok: true, vars: (raw instanceof Map) ? raw : [:], live: live]
}

// Add a local variable to a Rule Machine 5.1 rule.
//
// RM 5.1's variable wizard lives on selectActions. The flow:
// 1. Click `moreVar` button (state=moreVar) → opens wizard, exposes
// `hbVar` text input ("Name the local variable")
// 2. Write hbVar=<name> → exposes `varType` enum
// 3. Write varType=<Number|Decimal|String|Boolean|DateTime> → exposes
// `varValue` input (typed per varType)
// 4. Write varValue=<initial> → AUTO-COMMITS; the variable is added to
// state.allLocalVars and the wizard fields disappear, ready for
// the next variable. No explicit commit button.
//
// Verified live via probe on rule 1348:
// state.allLocalVars = {myCounter: {type:'integer', value:42}}
//
// Spec:
// {name: <varName>, type: 'Number'|'Decimal'|'String'|'Boolean'|'DateTime',
// value: <initial>}
//
// Returns [success, name, type, value, settingsApplied, settingsSkipped].
private boolean _rmConfigHasBrokenMarkers(config) {
    if (!(config instanceof Map)) return false
    def sections = config?.configPage?.sections ?: []
    def texts = (sections as List).collectMany { sect ->
        def fromBody = (sect?.body ?: [])
            .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
            .collect { it.description?.toString() ?: "" }
        def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
        fromBody + fromParagraphs
    }
    return texts.any { text ->
        ["**Broken Trigger**", "**Broken Action**", "**Broken Condition**"].any { text.contains(it) }
    }
}

// Shared dispatch-envelope for the local-variable add/remove shortcuts. Both fire
// a trailing updateRule (re-evaluates the rule's action map / subscriptions against
// the new or removed local), brief-settle, health-check, then assemble a response
// that ORs the inner helper's partial into the outer partial. opKind ('add'|'remove')
// drives the only two real differences: the variable SUB-SHAPE (add returns
// {name,type,value} + settings*, remove returns {name,deleted}) and the wording of
// the repairHint / note. varResult is the inner helper's Map (it may itself be a
// structured success:false + partial:true + repairHints when the commit/verify
// missed -- those slots ride out unchanged).
private void _rmDropPredClearPending(Integer appId) {
    synchronized (PRED_CLEAR_STORES) {
        Map pending = _rmPendingPredClearSnapshot()
        if (pending.remove(appId.toString()) != null) _rmCommitPredClearPending(pending)
    }
}

// A clear may finish after a concurrent rule edit published fresh recovery intent.
private void _rmDropPredClearPending(Integer appId, Object observedGeneration) {
    if (observedGeneration == null) return
    synchronized (PRED_CLEAR_STORES) {
        Map pending = _rmPendingPredClearSnapshot()
        String key = appId.toString()
        if (pending.get(key) != observedGeneration) return
        pending.remove(key)
        _rmCommitPredClearPending(pending)
    }
}

// Low-level reveal-step primitive for RM 5.1 progressive-disclosure wizard pages.
//
// Snapshot field names on a page, execute a trigger closure (button click or
// setting write), re-fetch the page, then find and return the newly-revealed
// input field whose name matches the given Java regex pattern.
//
// Callers rely on this when RM's next field name is firmware-assigned and must
// be schema-discovered rather than hardcoded -- for example, the cpType<P>.N
// slot allocated by moreParams, the xVar<digits>.N enum revealed after a
// uVar toggle, or any per-capability sub-picker that appears only after the
// capability selector commits. Returns the newly-revealed field if one appears;
// falls back to any matching field in the post-fetch schema (covers static schemas
// and always-visible-field paths). Returns null if no matching field exists at all.
//
// The fallback path (revealedNew ?: revealedAny) exists **because** some firmware
// versions expose fields unconditionally in the schema regardless of preceding writes
// (e.g. firmware that does not use progressive disclosure for a given capability).
// Without the fallback, a hub where the field is always visible would return null and
// fail-loud even though the field is present and writable. The primary path (revealedNew)
// handles the normal progressive-disclosure case; the fallback keeps the walker
// functional on static-schema firmware paths.
//
// @param appId    Rule Machine app ID
// @param page     Wizard page name (e.g. "doActPage", "STPage")
// @param pattern  Java regex the target field name must match
// @param trigger  Closure that causes the field to appear (write or click)
// @return         Map with three keys:
// input: matching input Map (or null if no match)
// visibleNames: List<String> of all input names in the post-trigger schema
// fallbackToExisting: Boolean -- true when the match came from the
// fallback "any matching field" path (revealedAny only, not
// revealedNew). Signals that the field was already visible BEFORE
// the trigger ran; callers tracking progressive-disclosure vs
// static-schema behaviour read this to emit informational
// sentinels. Does NOT imply failure.
// setVariable reveal helpers -- shared across the three schema-gated source modes
// (sourceVariable / fromDevice / math). Each mode writes its selector (numOp.<N>, or
// valStringOp.<N> for a String copy) then walks a sequence of schema-gated fields; these collapse
// the precondition check, the fetch-and-find-or-throw idiom, and the constant-vs-variable operand
// write that were triplicated across the modes.

// Precondition: the mode's selector (opField.<N>) must have landed for its gated fields to appear.
// If that write was skipped, attribute the failure precisely rather than letting the downstream
// reveal-miss blame a firmware gap. No-op when the selector landed.
Set _rmCollectPageInputNames(Integer appId, String pageName, Map cache = null) {
    def cfg = _rmFetchConfigJson(appId, pageName, cache)
    return (cfg?.configPage?.sections ?: []).collectMany { sec ->
        (sec?.input ?: []).collect { it?.name?.toString() }
    }.findAll { it } as Set
}

// The list of rule-health problems NEW vs `baselineHealth` -- the attributable-regression
// set the replace restore gates on. Two layers: a `now - baseline` STRING set-diff over
// issues + structuralIssues, plus a COUNT-aware broken-marker delta (the "broken markers in
// render" issue string collapses multiplicity, so a baseline that already carries one
// **Broken Condition** would mask a genuinely-NEW broken instance under a string diff -- a
// per-marker count INCREASE over baseline is a new break, named "<marker> (<now> vs <base>)").
// An UNCHANGED count or a pre-existing imbalance present in the baseline is NOT new, so a
// clean replace on an already-imbalanced rule is never spuriously rolled back. The restore
// message names ONLY these new issues, not the full current-issue list.
private List _rmReadExpressionTokens(Integer appId) {
    def text
    try { text = hubInternalGet("/app/ruleBuilderJson/${appId}") } catch (Exception e) {
        mcpLog("warn", "rm-native", "reading the Required Expression of app ${appId} failed: ${e.message}")
        return null
    }
    if (!text) return null
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(text) } catch (Exception e) { return null }
    if (!(parsed instanceof Map)) return null
    def toks = (parsed.eval instanceof Map) ? parsed.eval["0"] : null
    return (toks instanceof List) ? new ArrayList(toks as List) : []
}

// A token as the newToken picker spells it: condition ids as their number, operators verbatim.
private String _rmTokenValue(Object tok) {
    return tok?.toString()?.trim()
}

private boolean _rmTokensEqual(List a, List b) {
    if (a == null || b == null || a.size() != b.size()) return false
    for (int i = 0; i < a.size(); i++) {
        if (_rmTokenValue(a[i]) != _rmTokenValue(b[i])) return false
    }
    return true
}

// Condition ids in a token list (numeric tokens).
private List _rmTokenConditionIds(List toks) {
    return (toks ?: []).findAll { _rmTokenValue(it)?.isInteger() }.collect { _rmTokenValue(it) as Integer }
}

// Click a token-editor / STPage button and render STPage so the click takes effect. Returns the
// rendered page.
private Map _rmTokClick(Integer appId, String name, String stateAttr, Map cache = null) {
    _rmClickAppButton(appId, name, stateAttr, "STPage", cache)
    return _rmFetchConfigJson(appId, "STPage", cache)
}

private Set _rmPageInputNames(Map cfg) {
    return ((cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        .collect { it?.name?.toString() }.findAll { it }) as Set
}

// Open the token editor on a rule with a committed Required Expression. Throws when it does not
// open (nothing in the expression has changed at that point).
private void _rmTokEnterEditor(Integer appId, Map cache = null) {
    _rmTokClick(appId, "editST", "editST", cache)
    def names = _rmPageInputNames(_rmTokClick(appId, "editToken", "editToken", cache))
    // An expression just built through the new-expression selector reopens with an empty insert pending.
    if (names.contains("cancelInsert")) names = _rmPageInputNames(_rmTokClick(appId, "cancelInsert", "cancelInsert", cache))
    if (!names.contains("doneToken")) {
        throw new IllegalStateException("the Required Expression token editor did not open (STPage shows ${names.sort().join(', ')})")
    }
}

// Close the token editor and the Required Expression page: doneToken returns to the expression
// view, doneST commits it. The caller fires updateRule.
private void _rmTokLeaveEditor(Integer appId, Map cache = null) {
    def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage", cache))
    if (names.contains("doneToken")) _rmTokClick(appId, "doneToken", "doneToken", cache)
    _rmTokClick(appId, "doneST", "doneST", cache)
}

// Insert one token at `position` (an insertTok index; the token count appends). For `*` the
// condition form is left open for the caller to walk.
private void _rmTokInsert(Integer appId, int position, String value, Closure writeST, Map hrefParams, Map cache = null) {
    def cfg = _rmTokClick(appId, position.toString(), "insertTok", cache)
    def field = (cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        .find { it?.name?.toString()?.startsWith("newToken") }
    if (!field) {
        throw new IllegalStateException("the token editor did not offer a new token at position ${position} (STPage shows ${_rmPageInputNames(cfg).sort().join(', ')})")
    }
    // The picker's option VALUES (an operator / paren, a condition id, or `*`), whatever container
    // the page uses for them.
    def opts = field.options
    def offered = (opts instanceof Map) ? opts.keySet().collect { it?.toString() } :
        ((opts instanceof List) ? opts.collectMany { o ->
            (o instanceof Map) ? (o.containsKey("value") ? [o.value?.toString()] : o.keySet().collect { it?.toString() }) : [o?.toString()]
        } : [])
    offered = offered.findAll { it }
    if (offered && !offered.contains(value)) {
        throw new IllegalStateException("token '${value}' is not offered by the expression editor (offered: ${offered.join(', ')})")
    }
    def wr = writeST(hrefParams, field.name.toString(), value, "token(${value})")
    if (!(wr instanceof Map) || wr.persisted != true) {
        throw new IllegalStateException("the expression editor did not accept token '${value}' at position ${position}")
    }
}

// Inside the token editor: drop every token past target.size() (tokens this edit appended,
// including the blank token a cancelled condition leaves), then report whether the live tokens
// equal `target`.
List _rmDeleteExpressionConditions(Integer appId, Collection ids) {
    if (!ids) return []
    def st = _rmReadRuleState(appId)
    if (st == null) {
        mcpLog("warn", "rm-native", "not removing conditions ${ids} from app ${appId}: its expressions could not be read")
        return []
    }
    def inUse = ((st.eval instanceof Map) ? (st.eval as Map).values() : []).collectMany { v ->
        (v instanceof List) ? _rmTokenConditionIds(v as List).collect { it.toString() } : []
    } as Set
    ids = ids.findAll { !inUse.contains(it.toString()) }
    if (!ids) return []
    // RM ignores deleteCon until Manage Conditions is opened from STPage the way the UI does (pred:true).
    try {
        _rmNavigateToPage(appId, "STPage", "selectConditions", 0, "name", [pred: true])
    } catch (Exception navExc) {
        mcpLog("warn", "rm-native", "Required Expression edit: opening Manage Conditions on app ${appId} failed (${navExc.message})")
    }
    ids.each { id ->
        try {
            _rmClickAppButton(appId, id.toString(), "deleteCon", "selectConditions", null)
            _rmFetchConfigJson(appId, "selectConditions")
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "Required Expression edit: removing condition ${id} from app ${appId} failed (${e.message})")
        }
    }
    def remaining
    try { remaining = _rmFetchSettingsByName(appId)?.keySet() as Set } catch (Exception e) { remaining = null }
    if (remaining == null) return []
    return ids.findAll { !remaining.contains("rCapab_${it}".toString()) }.collect { it as Integer }
}

// Set the committed Required Expression to exactly `target` (condition ids already in the rule's
// pool plus operator / paren tokens), then commit and confirm. Rolls a replace back in place.
// Returns [restored: Boolean, error: String?].
private Map _rmTokSetExpression(Integer appId, List target) {
    // An empty target would delete every token and leave the rule ungated.
    if (!target) return [restored: false, error: "no original tokens were recorded, so nothing was changed"]
    def cache = [:]
    def hrefParams = [unUsed: null]
    def writeST = { Map params, String key, Object value, String label = null ->
        _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, key, value, cache)
    }
    try {
        def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage", cache))
        if (names.contains("cancelCapab")) names = _rmPageInputNames(_rmTokClick(appId, "cancelCapab", "cancelCapab", cache))
        if (names.contains("cancelInsert")) names = _rmPageInputNames(_rmTokClick(appId, "cancelInsert", "cancelInsert", cache))
        if (!names.contains("doneToken")) {
            if (names.contains("editToken")) _rmTokClick(appId, "editToken", "editToken", cache)
            else _rmTokEnterEditor(appId, cache)
        }
        // The target goes in front first and only the tokens after it are deleted, so the expression
        // is never empty if a click fails part way.
        target.eachWithIndex { tok, i -> _rmTokInsert(appId, i, _rmTokenValue(tok), writeST, hrefParams, cache) }
        for (int guard = 0; guard < 60; guard++) {
            def live = _rmReadExpressionTokens(appId)
            if (live == null) throw new IllegalStateException("the rule's expression state could not be read")
            if (live.size() <= target.size()) break
            _rmTokClick(appId, target.size().toString(), "deleteToken", cache)
        }
        _rmTokLeaveEditor(appId, cache)
        _rmClickAppButton(appId, "updateRule")
        def live = _rmReadExpressionTokens(appId)
        def committed = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
        if (_rmTokensEqual(live, target) && committed) return [restored: true]
        return [restored: false, error: "the rule's expression reads ${live} after the rollback (expected ${target})"]
    } catch (Exception e) {
        mcpLog("error", "rm-native", "Required Expression rollback on app ${appId} failed: ${e.message}")
        return [restored: false, error: e.message ?: e.toString()]
    }
}

// Roll a replaced Required Expression back to `origTokens` and report it in the replace envelope's
// terms: a failure while switching, a post-commit failure, and the patches[] batch end.
private Map _rmConditionTexts(Map state) {
    def out = [:]
    if (state?.get("capabsfalse") instanceof Map) (state.get("capabsfalse") as Map).each { id, txt ->
        out.put(id?.toString(), txt?.toString()?.replaceAll(/\(\s*<[A-Za-z][^>]*>[^()]*<\/[A-Za-z]+>\s*\)/, "")
            ?.replaceAll(/<\/?[A-Za-z!][^>]*>/, "")?.replaceAll(/\s+/, " ")?.trim())
    }
    return out
}

// Triggers (capabstrue), actions (actionList) and the condition pool (capabsfalse) live in RM app
// state, which a settings replay cannot write. Remove what the live rule has beyond the snapshot
// through RM's own delete paths, and name what the snapshot has that the live rule still lacks.
Map _rmReconcileRuleStructure(Integer appId, Map snapshot) {
    def appState = snapshot?.statusJson?.appState
    if (!(appState instanceof List)) return [:]
    def snap = [:]
    appState.each { if (it instanceof Map && it.name != null) snap.put(it.name.toString(), it.value) }
    if (!["capabstrue", "capabsfalse", "actionList"].any { snap.containsKey(it) }) return [:]
    def trigs = { Map st -> (st?.capabstrue instanceof Map) ? (st.capabstrue as Map).keySet().collect { it.toString() } : [] }
    def conds = { Map st -> (st?.capabsfalse instanceof Map) ? (st.capabsfalse as Map).keySet().collect { it.toString() } : [] }
    def acts = { Map st -> (st?.actionList instanceof List) ? (st.actionList as List).collect { it.toString() } : [] }
    def live = _rmReadRuleState(appId)
    if (live == null) return [structureRestored: false, structureError: "the rule's triggers and actions could not be read after the restore"]

    def removed = [triggers: [], actions: [], conditions: []]
    def failures = []
    (trigs(live) - trigs(snap)).each { idx ->
        try { _rmRemoveTrigger(appId, idx as Integer); removed.triggers << idx }
        catch (Exception e) { failures << "trigger ${idx} (${e.message})".toString() }
    }
    // Rows go one at a time, last first, so a removal never shifts a row still to go. The per-row
    // balance refusal would block each half of an extra IF/END-IF pair, so balance is
    // checked once for the whole set and the per-row check skipped only when the set leaves the
    // rule no less balanced than it is now.
    def extraActs = acts(live) - acts(snap)
    boolean setBalanced = false
    if (extraActs) {
        try {
            def ordered = _rmOrderedActionIndices(appId)
            if (ordered != null) {
                def byName = (_rmFetchStatusJson(appId)?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
                def removing = extraActs.collect { it as Integer } as Set
                def issuesNow = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, ([] as Set), ordered))
                def after = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, removing, ordered))
                setBalanced = (after - issuesNow).isEmpty()
            }
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: block balance check for app ${appId} failed (${e.message}); extra rows are removed one by one under the per-row check")
        }
    }
    boolean actionDeleteFailed = false
    extraActs.reverse().each { idx ->
        try { _rmDeleteAction(appId, idx as Integer, setBalanced); removed.actions << idx }
        catch (Exception e) { failures << "action ${idx} (${e.message})".toString(); actionDeleteFailed = true }
    }
    // Rows removed as a balanced set without the per-row check: a set left half done can leave an open block.
    def blockIssues = []
    if (setBalanced && actionDeleteFailed) {
        try {
            def ordered = _rmOrderedActionIndices(appId)
            if (ordered != null) {
                def byName = (_rmFetchStatusJson(appId)?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
                blockIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, ([] as Set), ordered))
            }
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: re-checking block balance on app ${appId} failed (${e.message})")
        }
    }
    // Conditions any expression uses (the Required Expression or an action's IF) stay.
    def inExpr = ((live.eval instanceof Map) ? (live.eval as Map).values() : []).collectMany { v ->
        (v instanceof List) ? _rmTokenConditionIds(v as List).collect { it.toString() } : []
    }
    def extraConds = conds(live) - conds(snap) - inExpr
    if (extraConds) removed.conditions = _rmDeleteExpressionConditions(appId, extraConds.collect { it as Integer }).collect { it as Integer }
    // Actions the backup also has go back to its order, one move up at a time.
    boolean moved = false
    String orderError = null
    def wantOrder = acts(snap)
    for (int p = 0; wantOrder.size() > 1 && p < wantOrder.size() && !orderError; p++) {
        def current = null
        try { current = _rmOrderedActionIndices(appId)?.collect { it.toString() }?.findAll { wantOrder.contains(it) } } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: reading the action order of app ${appId} failed (${e.message})")
        }
        if (current == null) { orderError = "the action order could not be read"; break }
        int pos = current.indexOf(wantOrder[p])
        while (pos > p && !orderError) {
            def mv
            try { mv = _rmMoveAction(appId, wantOrder[p] as Integer, "up") } catch (Exception e) { mv = [success: false, error: e.message] }
            if (mv?.success != true) orderError = "moving action ${wantOrder[p]} up failed (${mv?.error ?: mv?.verifyHint ?: 'not confirmed'})".toString()
            else { moved = true; pos-- }
        }
    }
    String updateRuleError = null
    if (removed.triggers || removed.actions || removed.conditions || moved) {
        try { _rmClickAppButton(appId, "updateRule") } catch (Exception e) { updateRuleError = e.message ?: e.toString() }
    }

    def after = _rmReadRuleState(appId)
    if (after == null) return [structureRestored: false, structureError: "the rule's triggers and actions could not be read after the restore"]
    def snapTrigText = (snap.capabstrue instanceof Map) ? snap.capabstrue as Map : [:]
    def out = [structureRestored: true]
    if (removed.triggers) out.removedTriggers = removed.triggers
    if (removed.actions) out.removedActions = removed.actions
    if (removed.conditions) out.removedConditionIds = removed.conditions
    def problems = []
    def missingTrigs = trigs(snap) - trigs(after)
    def missingActs = acts(snap) - acts(after)
    def extraTrigs = trigs(after) - trigs(snap)
    def extraActsLeft = acts(after) - acts(snap)
    def extraLeft = conds(after) - conds(snap) - inExpr
    if (missingTrigs) {
        out.missingTriggers = missingTrigs.collect { [index: it, text: snapTrigText.get(it)?.toString()] }
        problems << "the backup's trigger(s) ${missingTrigs.collect { snapTrigText.get(it) ?: it }.join('; ')} are not on the rule".toString()
    }
    if (missingActs) {
        out.missingActions = missingActs
        problems << "the backup's action row(s) ${missingActs.join(', ')} are not on the rule".toString()
    }
    if (extraTrigs || extraActsLeft || extraLeft) {
        out.extraRemaining = [triggers: extraTrigs, actions: extraActsLeft, conditions: extraLeft].findAll { k, v -> v }
        problems << "items the backup did not have could not be removed (${failures ? failures.join('; ') : out.extraRemaining})".toString()
    }
    def liveOrder = acts(after).findAll { wantOrder.contains(it) }
    if (liveOrder != wantOrder.findAll { liveOrder.contains(it) }) {
        out.actionOrder = [live: acts(after), backup: wantOrder]
        problems << "the actions run in the order ${acts(after)}, not the backup's ${wantOrder}${orderError ? ' (' + orderError + ')' : ''}".toString()
    }
    if (blockIssues) {
        out.structuralIssues = blockIssues
        problems << "removing the extra rows stopped part way, leaving the rule's blocks unbalanced: ${blockIssues.join('; ')} -- inspect and repair them in the native editor or with supported native document changes".toString()
    }
    if (updateRuleError) {
        out.updateRuleFailed = true
        problems << "the closing updateRule failed (${updateRuleError}), so the rule's subscriptions may not match -- click Update Rule".toString()
    }
    if (problems) {
        out.structureRestored = false
        out.structureError = problems.join("; ")
    }
    return out
}

private List _rmRenderExpression(List toks, Map texts) {
    return (toks ?: []).collect { t ->
        def v = _rmTokenValue(t)
        (v?.isInteger()) ? (texts?.get(v) ?: "#${v}".toString()) : v
    }
}

Map _rmReadRuleState(Integer appId) {
    try {
        def text = hubInternalGet("/app/ruleBuilderJson/${appId}")
        def parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null
        if (!(parsed instanceof Map)) mcpLog("warn", "rm-native", "the state of app ${appId} read as ${text ? 'non-JSON' : 'empty'}")
        return (parsed instanceof Map) ? (parsed as Map) : null
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "reading the state of app ${appId} failed: ${e.message}")
        return null
    }
}

// Re-walk one saved condition slot into the slot the wizard just opened: write each revealed field
// of the new slot from the snapshot's value for the same field of slot `srcIdx` (one field per
// render, in page order), then click hasAll. Returns the new slot index.
private Integer _rmReplayConditionSlot(Integer appId, Map srcSettings, int srcIdx, Closure writeST, Map cache) {
    Integer newIdx = null
    def written = [] as Set
    for (int iter = 0; iter < 30; iter++) {
        def inputs = (_rmFetchConfigJson(appId, "STPage", cache)?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        if (newIdx == null) {
            def rc = inputs.find { it?.name?.toString()?.startsWith("rCapab_") }
            def m = rc ? (rc.name.toString() =~ /^rCapab_(\d+)$/) : null
            if (!m || !m.matches()) throw new IllegalStateException("no new condition slot opened (STPage shows ${inputs.collect { it?.name }.findAll { it }.join(', ')})")
            newIdx = (m[0] as List)[1] as Integer
        }
        def next = null
        def nextValue = null
        for (inp in inputs) {
            def name = inp?.name?.toString()
            if (!name || written.contains(name) || inp?.type?.toString() == "button") continue
            def nm = name =~ /^(.*?[A-Za-z])(_?)(\d+)$/
            if (!nm.matches() || ((nm[0] as List)[3] as Integer) != newIdx) continue
            def srcKey = "${(nm[0] as List)[1]}${(nm[0] as List)[2]}${srcIdx}".toString()
            def v = srcSettings.get(srcKey)
            if (v == null || v == "" || (v instanceof Collection && v.isEmpty()) || (v instanceof Map && v.isEmpty())) continue
            def want = (v instanceof Map) ? (v as Map).keySet().collect { it?.toString() } : v
            // A field that already holds the saved value (a reused slot) needs no write.
            if (inp.value != null && _rmReplayValueMatches(inp.value, want)) { written << name; continue }
            next = name
            nextValue = want
            break
        }
        if (next != null) {
            def wr = writeST([unUsed: null], next, nextValue, next)
            if (!(wr instanceof Map) || wr.persisted != true) throw new IllegalStateException("the wizard did not accept ${next}=${nextValue} for the restored condition")
            written << next
            continue
        }
        if (inputs.any { it?.name?.toString() == "hasAll" }) {
            _rmClickAppButton(appId, "hasAll", null, "STPage", cache)
            _rmFetchConfigJson(appId, "STPage", cache)
            return newIdx
        }
        throw new IllegalStateException("the restored condition (from slot ${srcIdx}) could not be completed; STPage shows ${inputs.collect { it?.name }.findAll { it }.join(', ')}")
    }
    throw new IllegalStateException("the restored condition (from slot ${srcIdx}) did not complete within the wizard step limit")
}

private boolean _rmReplayValueMatches(Object current, Object want) {
    def norm = { o ->
        if (o instanceof Map) return (o as Map).keySet().collect { it?.toString() }.sort()
        if (o instanceof Collection) return (o as Collection).collect { it?.toString() }.sort()
        return [o?.toString()]
    }
    return norm(current) == norm(want)
}

// Device ids the snapshot's condition settings (for condIds) name that no longer exist on the hub.
List _rmExpressionDeadDevices(Map snapshot, List condIds) {
    def ids = (condIds ?: []).collect { it.toString() } as Set
    if (!ids) return []
    def settings = (snapshot?.configJson?.settings ?: [:]) as Map
    def recs = (snapshot?.statusJson?.appSettings ?: []).findAll { it instanceof Map }.collectEntries { [(it.name?.toString()): it] }
    def devices = [] as LinkedHashSet
    ((settings.keySet().collect { it.toString() }) + recs.keySet()).unique().each { String name ->
        // Condition settings end in _<id> (rDev_3); trigger settings (tDev3) share the digits but not the underscore.
        def m = name =~ /^[A-Za-z]+_(\d+)$/
        if (!m.matches() || !ids.contains((m[0] as List)[1].toString())) return
        def rec = recs.get(name)
        def v = settings.get(name)
        // A picker the status read does not list is known by its {id: label} value.
        if (!(rec ? _isDevicePickerType(rec.type?.toString()) : v instanceof Map)) return
        def list = (rec?.deviceIdsForDeviceList instanceof List && rec.deviceIdsForDeviceList) ? rec.deviceIdsForDeviceList : _devicePickerIds(v)
        (list instanceof List ? list : []).each { devices << it.toString() }
    }
    def cache = [:]
    return devices.findAll { _rmDeviceGone(it, cache) }.toList()
}

// Make the rule's Required Expression match a backup snapshot. Returns [:] when the snapshot carries
// no expression state (an older snapshot, a non-RM app) or neither side has an expression; else
// requiredExpressionRestored true|false, plus requiredExpressionRemoved (the snapshot had none),
// requiredExpressionError, preRestoreExpressionKept (a failed rebuild left the earlier expression),
// requiredExpressionPartial (a failed rebuild left part of the snapshot's expression live) and
// leftoverConditionIds.
Map _rmRestoreRequiredExpression(Integer appId, Map snapshot) {
    def appState = snapshot?.statusJson?.appState
    if (!(appState instanceof List)) return [:]
    def snapState = [:]
    appState.each { if (it instanceof Map && it.name != null) snapState.put(it.name.toString(), it.value) }
    def snapSettings = (snapshot?.configJson?.settings ?: [:]) as Map
    if (!snapState.containsKey("eval") && !snapSettings.containsKey("useST")) return [:]
    def snapTokens = (snapState.eval instanceof Map && (snapState.eval as Map)["0"] instanceof List) ? new ArrayList((snapState.eval as Map)["0"] as List) : []
    boolean snapHasRE = snapSettings.useST?.toString() == "true" && !snapTokens.isEmpty()
    def snapRendered = _rmRenderExpression(snapTokens, _rmConditionTexts(snapState))

    if (!snapHasRE) {
        // Nothing to rebuild; only a live expression the snapshot did not have needs removing.
        boolean liveHasRE
        try { liveHasRE = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage")) } catch (Exception e) {
            return [requiredExpressionRestored: false, requiredExpressionError: "the rule's Required Expression page could not be read after the restore (${e.message})"]
        }
        if (!liveHasRE) return [:]
        try {
            _rmTokClick(appId, "cancelST", "cancelST")
            _rmClickAppButton(appId, "updateRule")
            boolean gone = !_rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
            return gone ? [requiredExpressionRestored: true, requiredExpressionRemoved: true] :
                [requiredExpressionRestored: false, requiredExpressionError: "the snapshot has no Required Expression but removing the live one did not take"]
        } catch (Exception e) {
            return [requiredExpressionRestored: false, requiredExpressionError: "removing the live Required Expression failed (${e.message})"]
        }
    }
    def liveState = _rmReadRuleState(appId)
    if (liveState == null) return [requiredExpressionRestored: false, requiredExpressionError: "the rule's expression state could not be read after the restore"]
    def liveTokens = (liveState.eval instanceof Map && (liveState.eval as Map)["0"] instanceof List) ? (liveState.eval as Map)["0"] as List : []
    // A condition naming a deleted device cannot be rebuilt (the wizard takes the dead id and the
    // condition never renders), so nothing is touched.
    def deadDevices = _rmExpressionDeadDevices(snapshot, _rmTokenConditionIds(snapTokens))
    if (deadDevices) {
        def out = [requiredExpressionRestored: false,
                   requiredExpressionError: "the backup's expression names device(s) ${deadDevices.join(', ')} that no longer exist, so it was not rebuilt; the rule's expression was left as it is".toString()]
        if (!liveTokens.isEmpty()) out.preRestoreExpressionKept = true
        return out
    }
    boolean liveCommitted
    try { liveCommitted = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage")) } catch (Exception e) {
        return [requiredExpressionRestored: false, requiredExpressionError: "the rule's Required Expression page could not be read after the restore (${e.message})"]
    }
    if (liveCommitted && _rmRenderExpression(liveTokens, _rmConditionTexts(liveState)) == snapRendered) {
        return [requiredExpressionRestored: true]
    }

    def cache = [:]
    def writeST = { Map params, String key, Object value, String label = null ->
        _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, key, value, cache)
    }
    def hrefParams = [unUsed: null]
    // What the rule is committed to before the next step, so a failure trims back to it; the
    // conditions this rebuild added to the pool come out once that is confirmed.
    List baseTokens = liveCommitted ? new ArrayList(liveTokens) : []
    def poolBefore = _rmConditionTexts(liveState).keySet()
    try {
        int builtCount = 0
        int firstCond = snapTokens.findIndexOf { _rmTokenValue(it)?.isInteger() }
        int oldCount
        if (liveCommitted) {
            oldCount = liveTokens.size()
            _rmTokEnterEditor(appId, cache)
        } else {
            // No committed expression to edit: build the first condition with the new-expression
            // selector, commit it, then edit the rest in.
            oldCount = 0
            def w = writeST(hrefParams, "cond", "a", "cond")
            if (!(w instanceof Map) || w.persisted != true) throw new IllegalStateException("the new-expression selector did not open")
            def first = _rmReplayConditionSlot(appId, snapSettings, _rmTokenValue(snapTokens.get(firstCond)) as Integer, writeST, cache)
            writeST(hrefParams, "hasRule", "button", "hasRule")
            _rmTokClick(appId, "doneST", "doneST", cache)
            baseTokens = [first]
            _rmSubmitSubPageDone(appId, "STPage", "mainPage", "name", hrefParams, cache)
            _rmClickAppButton(appId, "updateRule")
            builtCount = 1
            if (snapTokens.size() > 1) _rmTokEnterEditor(appId, cache)
        }
        if (liveCommitted || snapTokens.size() > 1) {
            // Tokens before the first condition (a leading paren or NOT) go in front of it; the rest
            // append. With an old expression present, everything appends after it.
            def prefix = liveCommitted ? [] : (firstCond > 0 ? snapTokens[0..<firstCond] : [])
            def rest = liveCommitted ? snapTokens : snapTokens.subList(firstCond + 1, snapTokens.size())
            prefix.eachWithIndex { tok, i -> _rmTokInsert(appId, i, _rmTokenValue(tok), writeST, hrefParams, cache) }
            int position = oldCount + builtCount + prefix.size()
            rest.each { tok ->
                def v = _rmTokenValue(tok)
                if (v?.isInteger()) {
                    _rmTokInsert(appId, position, "*", writeST, hrefParams, cache)
                    _rmCacheInvalidate(cache, appId)
                    _rmReplayConditionSlot(appId, snapSettings, v as Integer, writeST, cache)
                } else {
                    _rmTokInsert(appId, position, v, writeST, hrefParams, cache)
                }
                position++
            }
            // The appended expression must read as the snapshot's before the old tokens go.
            def appendedState = _rmReadRuleState(appId)
            def appended = (appendedState?.eval instanceof Map && (appendedState.eval as Map)["0"] instanceof List) ? (appendedState.eval as Map)["0"] as List : null
            if (appended == null || appended.size() < oldCount) throw new IllegalStateException("the expression could not be read back after the rebuild")
            def appendedRendered = _rmRenderExpression(appended.drop(oldCount), _rmConditionTexts(appendedState))
            if (appendedRendered != snapRendered) {
                throw new IllegalStateException("the rebuilt expression reads '${appendedRendered.join(' ')}', not the snapshot's '${snapRendered.join(' ')}'")
            }
            oldCount.times { _rmTokClick(appId, "0", "deleteToken", cache) }
            _rmTokLeaveEditor(appId, cache)
            _rmClickAppButton(appId, "updateRule")
        }
        def after = _rmReadRuleState(appId)
        if (after == null) {
            return [requiredExpressionRestored: false, requiredExpressionError: "the rebuilt expression could not be read back to confirm it"]
        }
        def afterTokens = (after.eval instanceof Map && (after.eval as Map)["0"] instanceof List) ? (after.eval as Map)["0"] as List : []
        boolean committed = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
        if (committed && _rmRenderExpression(afterTokens, _rmConditionTexts(after ?: [:])) == snapRendered) {
            return [requiredExpressionRestored: true]
        }
        throw new IllegalStateException("the rebuilt expression reads '${_rmRenderExpression(afterTokens, _rmConditionTexts(after ?: [:])).join(' ')}', not the snapshot's '${snapRendered.join(' ')}'")
    } catch (Exception e) {
        mcpLog("error", "rm-native", "restore: rebuilding the Required Expression of app ${appId} failed: ${e.message}")
        // A failure after the rebuild finished (a later click or a verify read) leaves the snapshot's
        // expression in place: finish it rather than undo it.
        try {
            def nowState = _rmReadRuleState(appId)
            def nowTokens = (nowState?.eval instanceof Map && (nowState.eval as Map)["0"] instanceof List) ? (nowState.eval as Map)["0"] as List : null
            if (nowTokens && _rmRenderExpression(nowTokens, _rmConditionTexts(nowState)) == snapRendered) {
                _rmTokLeaveEditor(appId)
                _rmClickAppButton(appId, "updateRule")
                if (_rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))) return [requiredExpressionRestored: true]
            }
        } catch (Exception finishExc) {
            mcpLog("warn", "rm-native", "restore: finishing the rebuilt expression on app ${appId} failed (${finishExc.message})")
        }
        // Otherwise put back what was committed before the failure: the pre-restore expression, or
        // (no expression before) the first condition. The token editor sets any token order back,
        // including prefix tokens inserted in front of the base and old tokens already deleted.
        boolean trimmed = baseTokens.isEmpty()
        def keep = _rmTokenConditionIds(baseTokens)*.toString()
        def created = null
        def removed = []
        try {
            if (baseTokens.isEmpty()) {
                def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage"))
                if (names.contains("cancelCapab")) _rmTokClick(appId, "cancelCapab", "cancelCapab")
                def nowTokens = _rmReadExpressionTokens(appId)
                trimmed = nowTokens != null && nowTokens.isEmpty()
            } else {
                trimmed = _rmTokSetExpression(appId, baseTokens).restored == true
            }
            def after = _rmReadRuleState(appId)
            if (after != null) created = (_rmConditionTexts(after).keySet() - poolBefore).findAll { !keep.contains(it) }.collect { it.isInteger() ? (it as Integer) : it }
            if (trimmed && created) removed = _rmDeleteExpressionConditions(appId, created)
        } catch (Exception cleanupExc) {
            mcpLog("error", "rm-native", "restore: backing out of the failed rebuild on app ${appId} failed (${cleanupExc.message})")
            trimmed = false
        }
        def out = [requiredExpressionRestored: false]
        String state
        if (liveCommitted) {
            out.preRestoreExpressionKept = trimmed
            state = trimmed ? "the pre-restore expression was left in place" : "the live expression may be partly rebuilt -- inspect it with hub_get_app_config"
        } else if (!baseTokens.isEmpty()) {
            out.requiredExpressionPartial = true
            state = trimmed ? "only the snapshot's first condition is committed -- finish the expression with replaceRequiredExpression" :
                "part of the snapshot's expression is committed and the editor may be open -- inspect it with hub_get_app_config"
        } else {
            state = "no expression was committed, so the rule runs without its Required Expression (ungated) -- rebuild it with addRequiredExpression"
        }
        def leftover = (created ?: []).findAll { !(removed*.toString()).contains(it.toString()) }
        if (leftover) out.leftoverConditionIds = leftover
        out.requiredExpressionError = "rebuilding the Required Expression failed (${e.message}); ${state}".toString()
        return out
    }
}

// The token sequence a replace spec builds, in order: [cond: Map, condIdx: Integer, path: String]
// for each condition and [tok: String] for every operator and paren. Validates sub-expression operators.
def toolDeleteNativeApp(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    _requireUnprotectedAppDeletion(appId)
    def force = args?.force == true

    def backup
    try { backup = _rmBackupRuleSnapshot(appId, force ? "pre-forcedelete" : "pre-delete") }
    catch (Exception backupErr) {
        Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
        if (unavailable == null) throw backupErr
        return unavailable + [appId: appId]
    }

    try {
        if (force) {
            _rmForceDeleteApp(appId)
            _rmDropPredClearPending(appId)   // rule gone -> drop any deferred predCapabs-clear flag
            return [
                success: true,
                appId: appId,
                mode: "forcedelete",
                backup: backup,
                note: "App force-deleted. To restore, call hub_restore_backup with backupKey='${backup.backupKey}' (will recreate an empty app and apply saved settings).${_rmRetentionSuffix(backup)}"
            ]
        } else {
            def resp = _rmSoftDeleteApp(appId)
            // Soft delete returns {success: bool, message: ...} when it
            // parses. Surface the hub's own message so the user learns
            // why (e.g. "cannot delete — app has child devices").
            if (resp?.success == false) {
                return [
                    success: false,
                    appId: appId,
                    mode: "delete",
                    hubMessage: resp?.message,
                    backup: backup,
                    note: "Soft delete refused by hub. Pass force=true to override (app's children will also be removed)."
                ]
            }
            _rmDropPredClearPending(appId)   // rule gone -> drop any deferred predCapabs-clear flag
            return [
                success: true,
                appId: appId,
                mode: "delete",
                backup: backup,
                note: "App deleted."
            ]
        }
    } catch (Exception e) {
        return [
            success: false,
            appId: appId,
            error: e.message ?: e.toString(),
            backup: backup
        ]
    }
}

// hub_get_rule_health — public tool wrapper around _rmCheckRuleHealth.
// Read-only; doesn't take a backup. Returns the same {ok, issues, ...}
// native diagnostic report; native document writes perform their own checks.
def toolCheckRuleHealth(args) {
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    def source = (args?.source != null ? args.source.toString() : "auto")
    if (!(source in ["auto", "ruleBuilderJson", "configPage"])) {
        throw new IllegalArgumentException("source must be one of: auto (default), ruleBuilderJson, configPage")
    }
    def result = _rmCheckRuleHealth(appId, source)
    def status = null
    try {
        status = _rmFetchStatusJson(appId)
        // A readable statusJson with an ABSENT section means zero live entries -- a
        // schedule-only rule carries no eventSubscriptions list at all (and a stopped
        // rule drops both). null is reserved for the fetch itself failing.
        result.eventSubscriptionCount = (status?.eventSubscriptions instanceof List) ? status.eventSubscriptions.size() : 0
        result.scheduledJobCount = (status?.scheduledJobs instanceof List) ? status.scheduledJobs.size() : 0
    } catch (Exception statusErr) {
        result.eventSubscriptionCount = null
        result.scheduledJobCount = null
        // Half-checked marker, same contract as the two source reads: null counts with no
        // recorded reason read as "this rule has none", which is a different fact.
        def checkErrors = (result.checkErrors instanceof List) ? result.checkErrors : []
        checkErrors << "statusJson: ${statusErr.message ?: statusErr.toString()}".toString()
        result.checkErrors = checkErrors
    }
    // A plain suffix can be the user's name, even when the rule really is paused.
    // Only real markup proves which text is a runtime decoration; typed '<' is escaped.
    def rawLabel = result.label?.toString()
    Boolean pausedVerdict = result.paused instanceof Boolean ? result.paused :
        (status != null ? _readAppStateBoolean(status, "paused", null) : null)
    Boolean stoppedVerdict = status != null ? _readAppStateBoolean(status, "stopped", null) : null
    boolean visual = result.ruleFormat in ["vrb-classic", "vrb-graph"]
    while (!visual && rawLabel != null) {
        def decoration = rawLabel =~ /<[^>]+>\s*\((Paused|Stopped)\)\s*(?:<\/[^>]+>\s*)*$/
        if (!decoration.find()) break
        if (decoration.group(1) == "Stopped") {
            if (stoppedVerdict == null) stoppedVerdict = true
        } else if (pausedVerdict == null) pausedVerdict = true
        rawLabel = rawLabel.substring(0, decoration.start()).trim()
    }
    // Explicit state outranks older display markup; unreadable state still uses it.
    // A readable status with no stopped flag means never stopped. Pause absence
    // is resolved only by the recognizable native RM compiled-state reader;
    // other missing or malformed pause evidence remains unknown here.
    if (stoppedVerdict == null && status != null) stoppedVerdict = false
    result.stopped = stoppedVerdict
    result.paused = pausedVerdict
    if (!visual && rawLabel != null) result.label = rawLabel.replaceAll(/<[^>]+>/, "").trim()
    return result
}

// hub_list_rule_local_variables -- read a rule's local variables from its statusJson appState.
// Pure read; no wizard. Returns {appId, localVariables:[{name, type, value}], total}; an absent
// allLocalVars map means the rule has no locals (an empty list, not an error).
def toolListRuleLocalVariables(args) {
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    // _rmReadLocalVarsMap throws nothing (it wraps the status read); a read failure
    // surfaces as ok:false. Re-raise it here so the pure-read tool reports the read
    // error rather than silently returning an empty list that looks like "no locals".
    def lvRead = _rmReadLocalVarsMap(appId)
    if (!lvRead.ok) {
        throw new IllegalStateException("hub_list_rule_local_variables: could not read rule ${appId} status (${lvRead.error}).")
    }
    def localVariables = []
    lvRead.vars.each { lvName, lvMeta ->
        def live = lvRead.live?.get(lvName?.toString())
        localVariables << [
            name: lvName?.toString(),
            type: (lvMeta instanceof Map ? lvMeta?.type?.toString() : null),
            value: (live instanceof Map && live.containsKey("value")) ? live.value : (lvMeta instanceof Map ? lvMeta?.value : null)
        ]
    }
    localVariables = localVariables.sort { it.name }
    return [appId: appId, localVariables: localVariables, total: localVariables.size()]
}

// ==================== PER-TOOL METADATA (issue #209) ====================

def _readOnlyToolNames_partNativeRM() {
    // Read-only classification for this library's tools (issue #209: per-tool metadata lives
    // with the tool), contributed to the app's getReadOnlyToolNames() aggregator.
    return [
        // Native rules (read) -- hub_get_rule_health inspects only;
        // hub_list_rule_local_variables reads the rule's statusJson only.
        // (hub_export_native_app -- in McpAppClonerLib -- instantiates a cloner app + persists, so it stays write.)
        "hub_list_rules", "hub_get_rule_health", "hub_list_rule_local_variables"
    ]
}

def _idempotentWriteToolNames_partNativeRM() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Native rules / classic apps
        "hub_set_rule_paused", "hub_set_rule_private_boolean", "hub_set_app_disabled"
    ]
}

def _toolDisplayMeta_partNativeRM() {
    // Human-facing title/summary per tool -- merged into the app's getToolDisplayMeta() aggregator.
    return [
        // Native Rule Machine + classic apps
        hub_list_rules: [title: "List Rule Machine Rules", summary: "List all Rule Machine rules."],
        hub_call_rule: [title: "Trigger Rule Machine Rule", summary: "Trigger one or more Rule Machine rules, run their actions, or stop them."],
        hub_set_rule_paused: [title: "Pause or Resume Rule", summary: "Pause or resume one or more Rule Machine rules in one call."],
        hub_set_rule_private_boolean: [title: "Set Rule Private Boolean", summary: "Set the private boolean of one or more Rule Machine rules."],
        hub_get_rule_health: [title: "Get Rule Health", summary: "Read-only health check on any installed app."],
        hub_list_rule_local_variables: [title: "List Rule Local Variables", summary: "Read-only list of a Rule Machine rule's local variables."],
        hub_delete_native_app: [title: "Delete Native App", summary: "Delete a classic native app (auto-snapshot first)."],
        hub_set_app_disabled: [title: "Enable or Disable App", summary: "Enable or disable any installed app without deleting it (reversible)."]
    ]
}
