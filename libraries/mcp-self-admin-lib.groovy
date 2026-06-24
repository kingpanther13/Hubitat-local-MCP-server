library(name: "McpSelfAdminLib", namespace: "mcp", author: "kingpanther13", description: "MCP self-administration tool implementations (hub_update_mcp_settings + the hub_update_package Developer Mode deploy) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolUpdateMcpSettings(args) {
    // IllegalArgumentException (not IllegalStateException) so the dispatcher routes this
    // through the clean -32602 Invalid params branch in handleToolsCall — same exception
    // type the other gates throw (requireDestructiveConfirm, the central master gate).
    // Toggle-off is a config refusal, not an unexpected runtime error worth a stack trace.
    if (!settings.enableDeveloperMode) {
        throw new IllegalArgumentException("Developer Mode tools are disabled. Enable 'Developer Mode Tools' in MCP rule app settings to use hub_update_mcp_settings.")
    }
    requireDestructiveConfirm(args.confirm)

    if (!args.settings || !(args.settings instanceof Map) || args.settings.isEmpty()) {
        throw new IllegalArgumentException("settings must be a non-empty map of {settingName: newValue}")
    }

    // Allowlist of settings that can be modified via this tool, with their Hubitat input
    // type (matches the input "<key>", "<type>", ... declarations in the mainPage section).
    // useGateways and publishOutputSchemas are allowlisted: they only reshape tools/list
    // (gateway vs flat; whether outputSchema is advertised) — same class as
    // enableCustomRuleEngine, no write-path or lockout risk; clients must reconnect
    // afterward to pick up the new tool surface.
    // enableMandatoryBPS (issue #299) is allowlisted as an ESCAPE HATCH: hub_update_mcp_settings is
    // itself exempt from the best-practice gate, so letting the AI self-disable the (default-ON) gate
    // here is the documented un-lock path, not a footgun. (The reactive hint has no toggle -- always on.)
    // Excluded:
    //   enableWrite          — would footgun: could disable own write path mid-session
    //   enableDeveloperMode  — lockout protection (must remain UI-only to disable)
    //   selectedDevices      — capability multi-select, separate tool planned (Developer Mode follow-up)
    //   disabled_tools / disabled_gateways — could self-disable this tool; UI-only (Advanced page)
    def allowedSettings = [
        "mcpLogLevel":            "enum",
        "debugLogging":           "bool",
        "maxCapturedStates":      "number",
        "loopGuardMax":           "number",
        "loopGuardWindowSec":     "number",
        "enableRead":             "bool",
        "enableCustomRuleEngine": "bool",
        "useGateways":            "bool",
        "publishOutputSchemas":   "bool",
        "enableMandatoryBPS":     "bool"
    ]

    // Validate, coerce, and stage each update. Validation is fully atomic — every key
    // and every value is checked BEFORE any app.updateSetting() fires, so a single bad
    // entry in a multi-key batch can't leave the hub in a half-applied state. This
    // includes per-key sub-validation that would otherwise only fire during apply
    // (e.g. mcpLogLevel against getLogLevels()).
    //
    // Type coercion is also mandatory: a JSON-RPC client (e.g. a curl-based CI script)
    // may send "true"/"false" or "50" as strings instead of native bool/number. Without
    // coercion, app.updateSetting(key, [type:'bool', value:'false']) writes the string
    // "false", which is truthy in Groovy — silently flipping the toggle to enabled
    // when the caller meant disabled.
    def updates = [:]
    args.settings.each { key, value ->
        def keyStr = key.toString()
        if (!allowedSettings.containsKey(keyStr)) {
            throw new IllegalArgumentException("Setting '${keyStr}' is not allowed for self-modification via hub_update_mcp_settings. Allowed: ${allowedSettings.keySet().sort().join(', ')}")
        }
        def coerced = coerceSettingValue(keyStr, value, allowedSettings[keyStr])
        // Per-key sub-validation that the apply step would otherwise discover too late.
        // mcpLogLevel must be one of the configured log levels — if 'blarg' slipped through
        // the enum coerce and only failed inside toolSetLogLevel during apply, any prior
        // app.updateSetting() calls in the same batch would have already landed.
        if (keyStr == "mcpLogLevel" && !getLogLevels().contains(coerced)) {
            throw new IllegalArgumentException("Setting 'mcpLogLevel' must be one of ${getLogLevels()}, got: ${coerced}")
        }
        updates[keyStr] = coerced
    }

    // Two-phase audit so post-mortem investigators can distinguish "validation accepted N
    // keys" (attempted) from "all N writes landed" (applied). Both fire at WARN.
    mcpLog("warn", "developer-mode", "hub_update_mcp_settings: attempted=${updates}")

    // Apply each update via app.updateSetting() — the documented Hubitat sandbox API for
    // self-modifying app settings. mcpLogLevel needs special handling because the runtime
    // log threshold is cached in state.debugLogs.config (UI display reads from settings).
    //
    // Apply order is intentional: app.updateSetting calls first, then mcpLogLevel last via
    // toolSetLogLevel. If toolSetLogLevel ever evolves to throw on an unexpected condition,
    // the rest of the batch has already landed. Per-key validation above is the primary
    // safeguard; this ordering is belt-and-suspenders.
    updates.each { key, value ->
        if (key == "mcpLogLevel") {
            // Delegate to existing helper — it updates both state cache + setting
            toolSetLogLevel([level: value.toString()])
        } else {
            app.updateSetting(key, [type: allowedSettings[key], value: value])
        }
    }

    mcpLog("warn", "developer-mode", "hub_update_mcp_settings: applied=${updates}")

    def updateCount = updates.size()
    def settingWord = updateCount == 1 ? "setting" : "settings"
    return [
        success: true,
        updated: updates,
        message: "Updated ${updateCount} ${settingWord}. MCP clients (Claude Code, etc.) may need to reconnect to refresh cached tool schemas if you toggled an enable* flag, useGateways, or publishOutputSchemas."
    ]
}

// Coerce + validate a single setting value into the type the Hubitat input expects.
// Throws IllegalArgumentException for unrepresentable values (rather than silently
// substituting a default — that would mask the caller's intent and recreate the same
// kind of footgun the bool string-truthiness bug is fixing).
//
// - bool: accepts native Boolean, plus case-insensitive "true"/"false" strings.
// - number: accepts native Number, plus integer/decimal-formatted strings.
// - enum: passed through; downstream (e.g. toolSetLogLevel) does its own enum-membership
//         check against a tool-specific allowed list.
private coerceSettingValue(String key, value, String type) {
    if (value == null) {
        throw new IllegalArgumentException("Setting '${key}' value cannot be null")
    }
    switch (type) {
        case "bool":
            if (value instanceof Boolean) return value
            def s = value.toString().toLowerCase()
            if (s == "true") return true
            if (s == "false") return false
            throw new IllegalArgumentException("Setting '${key}' expects a boolean (true/false), got: ${value} (${value.class.simpleName})")

        case "number":
            if (value instanceof Number) return value
            def s = value.toString()
            if (s.isInteger()) return s.toInteger()
            if (s.isLong()) return s.toLong()
            if (s.isBigDecimal()) return s.toBigDecimal()
            throw new IllegalArgumentException("Setting '${key}' expects a number, got: ${value} (${value.class.simpleName})")

        case "enum":
            // Pass through as String — downstream validates against tool-specific enum set.
            return value.toString()

        default:
            // Defensive: unknown type from the allowlist map should not reach here.
            return value
    }
}

// Canonical raw-source base for the MCP package. Per-call URLs are "${base}/${ref}/${path}";
// mirrors packageManifest.json's location (.../Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy).
// Overridable per call via baseUrl for forks / CI branches on a different remote.
def getPackageSourceBase() {
    return "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server"
}

// Re-anchor a packageManifest.json item location to the deploy `ref`. Manifest locations
// are full raw URLs committed against a fixed branch (e.g. .../main/bundles/mcp-libraries.zip);
// strip the scheme://host/owner/repo/<branch>/ prefix to the repo-relative path, then rebuild
// against base+ref. This is what lets hub_update_package install an UNMERGED PR's artifacts --
// HPM repair trusts the manifest's branch-pinned URL; we can't. Same 4-segment prefix-strip as
// .github/scripts/mcp_watchdog_deploy.sh (that one hard-codes the raw.githubusercontent.com host;
// this regex is host-agnostic). Returns null when the location is not in the expected
// scheme://host/owner/repo/ref/<path> shape, so the caller fails closed.
def _reanchorToRef(location, String base, String ref) {
    if (!(location instanceof String) || !location.trim()) return null
    def loc = location.trim()
    def rel = loc.replaceFirst(/^https?:\/\/[^\/]+\/[^\/]+\/[^\/]+\/[^\/]+\//, '')
    if (rel == loc || !rel) return null
    return "${base}/${ref}/${rel}".toString()
}

// Bot-published bundle artifact URL for a ref (the bundle-artifacts branch). PRs stopped
// rebuilding the committed bundles/*.zip (the post-merge workflow owns it on main), so the
// publish-bundle-artifact workflow builds the zip on every library-touching push and stores
// it under shas/<full-sha>/ and branches/<branch>/ -- the fresh-zip source that lets this
// tool install an unmerged PR's libraries commit by commit. The zip KEEPS its manifest
// basename inside the artifact path: the hub appears to key the bundle entity on the zip
// filename, so a renamed zip would import as a SECOND entity (duplicate libraries) instead
// of updating the existing one. Returns null when the manifest location shape is unusable.
def _bundleArtifactUrlForRef(location, String base, String ref) {
    if (!(location instanceof String) || !location.trim() || !ref) return null
    def loc = location.trim()
    def rel = loc.replaceFirst(/^https?:\/\/[^\/]+\/[^\/]+\/[^\/]+\/[^\/]+\//, '')
    if (rel == loc || !rel) return null
    def baseName = rel.tokenize('/').last()
    // Full 40-hex refs key by SHA; everything else is treated as a branch name. A SHORT
    // sha has no artifact path (the workflow stores full SHAs only) -- it probes the
    // branches/ path, misses, and falls back to the committed zip.
    def keyPath = (ref ==~ /[0-9a-f]{40}/) ? "shas/${ref}" : "branches/${ref}"
    return "${base}/bundle-artifacts/${keyPath}/${baseName}".toString()
}

// Probe the artifact's tiny .size marker (written atomically alongside the zip by the
// publish workflow) instead of downloading the ~1MB zip just to check existence. An
// integer body means the zip is there; a 404 (throws), an HTML error page, or any
// non-integer body means no artifact -- the caller falls back to the committed zip.
def _bundleArtifactExists(String artifactUrl) {
    try {
        def r = _httpFetchUrl("${artifactUrl}.size")
        return ((r?.status as Integer) == 200) && (r?.body?.toString()?.trim() ==~ /\d+/)
    } catch (Exception e) {
        // A legitimate miss (404) and a transient probe failure (rate limit, network blip) both
        // land here; log so a fallback caused by a TRANSIENT failure is at least diagnosable
        // instead of silently indistinguishable from "no artifact published for this ref".
        mcpLog("warn", "developer-mode", "_bundleArtifactExists: probe of ${artifactUrl}.size failed (${e.toString()}) -- treating as no artifact; the bundle leg falls back to the committed zip")
        return false
    }
}

// Parse "#include namespace.Name" directives from Groovy source. Returns an ordered,
// de-duplicated list of include tokens ("namespace.Name") in first-seen order so the
// install order is deterministic across runs.
def _parseIncludeDirectives(String source) {
    def tokens = []
    def seen = [] as Set
    def matcher = (source =~ /(?m)^[ \t]*#include[ \t]+([A-Za-z0-9_]+\.[A-Za-z0-9_]+)[ \t]*$/)
    while (matcher.find()) {
        def tok = matcher.group(1)
        if (!seen.contains(tok)) { seen << tok; tokens << tok }
    }
    return tokens
}

// Resolve the MCP server's own Apps Code CLASS id (distinct from the running INSTANCE
// id) by matching definition()'s namespace+name against /hub2/userAppTypes -- the same
// lookup the e2e deploy script does. Returns the id String, or null when it can't be
// resolved (the caller fails closed and writes nothing).
def _resolveSelfAppClassId() {
    try {
        def responseText = hubInternalGet("/hub2/userAppTypes")
        if (!responseText) return null
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof List)) return null
        def match = parsed.find { it?.namespace == "mcp" && it?.name == "MCP Rule Server" }
        return match?.id?.toString()
    } catch (Exception e) {
        // Reached from the code-update path too (not just hub_update_package); keep the label neutral.
        mcpLog("warn", "hub-admin", "_resolveSelfAppClassId: self app-class lookup failed (${e.toString()}) -- self-deploy detection / #237 compile-error capture is skipped for this update")
        return null
    }
}

def toolUpdatePackage(args) {
    // Developer-Mode gate (mirrors toolUpdateMcpSettings): IllegalArgumentException so
    // the dispatcher routes a toggle-off refusal through the clean -32602 Invalid params
    // branch rather than a 500. The tool is ALSO catalog-hidden when dev mode is off
    // (getDeveloperModeOnlyToolNames) -- this is belt-and-suspenders for a direct call.
    if (!settings.enableDeveloperMode) {
        throw new IllegalArgumentException("Developer Mode tools are disabled. Enable 'Developer Mode Tools' in MCP rule app settings to use hub_update_package.")
    }

    def ref = args?.ref
    if (!(ref instanceof String) || !ref.trim()) {
        throw new IllegalArgumentException("ref is required: a branch, tag, or commit SHA to deploy (e.g. 'main' or a PR head SHA).")
    }
    ref = ref.trim()
    def dryRun = (args?.dryRun == true)

    // Non-dry-run = a real write: enforce confirm + a <24h backup ONCE here (fail fast,
    // before any network I/O). The inner library/app calls re-check the same gate and
    // pass cleanly since the timestamp is already fresh. Dry run writes nothing, so it
    // skips the gate -- it only fetches + parses + plans.
    if (!dryRun) {
        requireDestructiveConfirm(args?.confirm)
    }

    def base = (args?.baseUrl instanceof String && args.baseUrl.trim())
        ? args.baseUrl.trim().replaceAll('/+$', '')
        : getPackageSourceBase()
    // .toString() throughout: _fetchSourceFromUrl / hub_install_bundle / hub_update_app
    // reject a GString importUrl (instanceof String is false for GStringImpl).
    def appUrl = "${base}/${ref}/hubitat-mcp-server.groovy".toString()

    // Fetch the SELF app source at ref first, ONLY to read its #include directives for the
    // bundle-coverage guard below. A fetch failure is a clean abort -- nothing written.
    def appSource
    try {
        appSource = _fetchSourceFromUrl(appUrl)
    } catch (Exception e) {
        return [
            success: false, aborted: true, abortReason: "app_source_fetch_failed", ref: ref, appUrl: appUrl,
            error: "Failed to fetch app source at ref '${ref}' (${appUrl}): ${e.message ?: e.toString()}. Nothing was changed."
        ]
    }
    def includeTokens = _parseIncludeDirectives(appSource)

    // Fetch packageManifest.json AT THE REF -- the authoritative list of what to deploy
    // (HPM's manifest, but at the PR ref so an unmerged PR installs). Fail closed on a
    // fetch or parse error: deploying without the manifest could miss a bundle or app.
    def manifestUrl = "${base}/${ref}/packageManifest.json".toString()
    def manifestText
    try {
        manifestText = _fetchSourceFromUrl(manifestUrl)
    } catch (Exception e) {
        return [
            success: false, aborted: true, abortReason: "manifest_fetch_failed", ref: ref, manifestUrl: manifestUrl,
            error: "Failed to fetch packageManifest.json at ref '${ref}' (${manifestUrl}): ${e.message ?: e.toString()}. Nothing was changed."
        ]
    }
    def manifest
    try {
        manifest = new groovy.json.JsonSlurper().parseText(manifestText)
    } catch (Exception e) {
        manifest = null
    }
    if (!(manifest instanceof Map)) {
        return [
            success: false, aborted: true, abortReason: "manifest_unparseable", ref: ref, manifestUrl: manifestUrl,
            error: "packageManifest.json at ref '${ref}' was not a JSON object. Nothing was changed."
        ]
    }

    // Plan the bundle leg: prefer the bot-published per-ref artifact (fresh for any
    // same-repo ref -- see _bundleArtifactUrlForRef). The fallback depends on the
    // MANIFEST SHAPE AT THE REF, which is what keeps old refs working unchanged:
    //   * unified-delivery manifests (location on the bundle-artifacts branch) fall
    //     back to that location AS-IS -- the branches/main zip, exactly what HPM users
    //     currently install; correct whenever the ref's libraries match current main,
    //     surfaced via bundleFreshnessWarning otherwise.
    //   * legacy manifests (in-tree location) fall back to the zip COMMITTED at the
    //     ref via _reanchorToRef -- correct whenever the ref did not change libraries/
    //     relative to its base.
    def manifestBundles = (manifest.bundles instanceof List) ? manifest.bundles : []
    def plannedBundles = []
    for (b in manifestBundles) {
        def loc = (b?.location instanceof String) ? b.location.trim() : null
        def entry
        if (loc && loc.contains("/bundle-artifacts/")) {
            entry = [name: (b?.name ?: b?.id), url: loc, source: "manifest-current"]
        } else {
            def url = _reanchorToRef(loc, base, ref)
            if (!url) {
                return [
                    success: false, aborted: true, abortReason: "bundle_location_unusable", ref: ref,
                    error: "Bundle '${b?.name ?: b?.id ?: '?'}' in packageManifest.json has an unusable location '${b?.location}' (expected a scheme://host/owner/repo/ref/<path> raw URL or a bundle-artifacts URL). Nothing was changed."
                ]
            }
            entry = [name: (b?.name ?: b?.id), url: url, source: "committed-at-ref"]
        }
        def artifactUrl = _bundleArtifactUrlForRef(loc, base, ref)
        if (artifactUrl && _bundleArtifactExists(artifactUrl)) {
            entry.url = artifactUrl
            entry.source = "bundle-artifacts"
        }
        plannedBundles << entry
    }

    // Coverage guard (mirrors mcp_watchdog_deploy.sh): if the app #includes libraries but
    // the manifest declares NO bundle to deliver them, a deploy would leave the #includes
    // unresolved and the app would not compile. Refuse before any write.
    if (!includeTokens.isEmpty() && plannedBundles.isEmpty()) {
        return [
            success: false, aborted: true, abortReason: "bundle_required_but_undeclared", ref: ref, includes: includeTokens,
            error: "App source #includes ${includeTokens.size()} library(ies) (${includeTokens.join(', ')}) but packageManifest.json at ref '${ref}' declares no bundle to deliver them. A bundle-less deploy would leave the #includes unresolved and the app would not compile. Nothing was changed."
        ]
    }

    // Plan the app leg: resolve every manifest app's Apps Code CLASS id by namespace+name
    // (one /hub2/userAppTypes fetch). The SELF app (mcp / "MCP Rule Server", the running
    // parent) is flagged so it can be deployed LAST -- its recompile drops the in-flight
    // response (#237), so it must be the final act with the rest already in place.
    def manifestApps = (manifest.apps instanceof List) ? manifest.apps : []
    def appTypes
    try {
        def typesText = hubInternalGet("/hub2/userAppTypes")
        appTypes = typesText ? new groovy.json.JsonSlurper().parseText(typesText) : null
    } catch (Exception e) {
        appTypes = null
    }
    if (!(appTypes instanceof List)) {
        return [
            success: false, aborted: true, abortReason: "app_class_unresolved", ref: ref,
            error: "Could not list Apps Code classes via /hub2/userAppTypes to resolve the manifest's apps. Nothing was changed; the app remains updatable via hub_update_app."
        ]
    }
    def plannedApps = []
    for (a in manifestApps) {
        def match = appTypes.find { it?.namespace == a?.namespace && it?.name == a?.name }
        def classId = match?.id?.toString()
        def url = _reanchorToRef(a?.location, base, ref)
        if (!classId || !url) {
            return [
                success: false, aborted: true, abortReason: "app_class_unresolved", ref: ref,
                error: "Could not resolve app '${a?.namespace}:${a?.name}' (class id: ${classId ?: 'unresolved'}, url: ${url ?: 'unusable location'}). Nothing was changed; the app remains updatable via hub_update_app."
            ]
        }
        plannedApps << [
            name: a?.name, namespace: a?.namespace, classId: classId, url: url,
            isSelf: (a?.namespace == "mcp" && a?.name == "MCP Rule Server")
        ]
    }
    // Non-self apps first, the self app last (so the self recompile is the final act).
    def orderedApps = plannedApps.findAll { !it.isSelf } + plannedApps.findAll { it.isSelf }

    if (dryRun) {
        return [
            success: true, dryRun: true, ref: ref, appUrl: appUrl, includes: includeTokens,
            plannedBundles: plannedBundles, plannedApps: orderedApps,
            message: "Dry run: would install ${plannedBundles.size()} bundle(s) then deploy ${orderedApps.size()} app(s) (self app last) to ref ${ref}. No changes made."
        ]
    }

    // BUNDLES FIRST (override). HPM repair installs every manifest bundle via the hub's
    // uploadZipFromUrl endpoint, which overwrites the libraries in place. Abort the WHOLE
    // deploy on the first failure, BEFORE touching any app, so an app is never saved
    // against a missing/stale library. We trust the hub's success signal (no separate
    // library-presence re-read): uploadZipFromUrl unpacks + registers the libraries
    // SYNCHRONOUSLY before returning success -- the same signal HPM relies on -- and the
    // self app #includes them, so the final backstop is its compile: a missing #include
    // makes the self app save fail (app_update_failed/threw, reported), never silently land.
    def bundleResults = []
    for (b in plannedBundles) {
        def r
        try {
            r = toolInstallBundle([importUrl: b.url, confirm: true])
        } catch (Exception e) {
            mcpLog("warn", "developer-mode", "hub_update_package: bundle ${b.url} install threw: ${e.toString()}")
            return [
                success: false, aborted: true, abortReason: "bundle_install_threw", ref: ref, bundles: bundleResults,
                error: "Bundle ${b.name ?: b.url} failed to install: ${e.message ?: e.toString()}. Apps NOT touched -- they remain as-is and updatable via hub_update_app."
            ]
        }
        def ok = (r?.success == true)
        bundleResults << [name: b.name, url: b.url, source: b.source, success: ok, error: (ok ? null : (r?.error ?: r?.message))]
        if (!ok) {
            return [
                success: false, aborted: true, abortReason: "bundle_install_failed", ref: ref, bundles: bundleResults,
                error: "Bundle ${b.name ?: b.url} install reported failure: ${r?.error ?: r?.message ?: 'unknown'}. Apps NOT touched -- they remain as-is and updatable via hub_update_app."
            ]
        }
    }

    // APPS LAST, the self app last of all. Each non-self app must succeed before the self
    // app is touched (fail-closed: a child-app failure never advances to the self deploy,
    // so the running server is left as-is and updatable). Reuse hub_update_app's exact
    // update path (auto-backup + post-save verify + #237 compile-error capture).
    def appResults = []
    for (a in orderedApps) {
        def r
        try {
            r = toolUpdateAppCode([appId: a.classId, importUrl: a.url, confirm: true])
        } catch (Exception e) {
            if (a.isSelf) {
                // A self-update recompiles the running server mid-call, so the response can
                // be lost even though the save landed. Surface it: the bundle(s) and other
                // apps are already in place, and hub_update_app remains available to retry.
                appResults << [name: a.name, namespace: a.namespace, classId: a.classId, isSelf: true, success: false, error: (e.message ?: e.toString())]
                return [
                    success: false, partial: true, abortReason: "app_update_threw", ref: ref,
                    bundles: bundleResults, apps: appResults,
                    error: "Bundle(s) and other apps installed, but the self app update call did not return cleanly (likely the self-update recompile): ${e.message ?: e.toString()}. Verify with hub_get_source; re-run hub_update_app(importUrl) if needed."
                ]
            }
            mcpLog("warn", "developer-mode", "hub_update_package: app ${a.namespace}:${a.name} update threw: ${e.toString()}")
            return [
                success: false, aborted: true, abortReason: "app_update_threw", ref: ref,
                bundles: bundleResults, apps: appResults,
                error: "App ${a.namespace}:${a.name} update threw before the self app was touched: ${e.message ?: e.toString()}. The self (server) app was NOT touched -- it remains as-is and updatable via hub_update_app."
            ]
        }
        def ok = (r?.success == true)
        appResults << [name: a.name, namespace: a.namespace, classId: a.classId, isSelf: a.isSelf, success: ok, app: r]
        if (!ok) {
            if (a.isSelf) {
                // Self app is last; a clean failure return (not a throw) is surfaced as-is. Same
                // partial state as the throw path above (bundles + other apps landed, self did not
                // update), so flag partial=true too -- the difference is only threw vs returned-false.
                mcpLog("warn", "developer-mode", "hub_update_package: ref=${ref} self app update reported failure")
                return [
                    success: false, partial: true, ref: ref, includes: includeTokens, bundles: bundleResults, apps: appResults, app: r,
                    message: "Bundle(s) + other apps installed; the self (server) app update reported failure -- see apps[].app.error. hub_update_app remains available to retry."
                ]
            }
            return [
                success: false, aborted: true, abortReason: "app_update_failed", ref: ref,
                bundles: bundleResults, apps: appResults,
                error: "App ${a.namespace}:${a.name} update reported failure: ${r?.error ?: r?.message ?: 'unknown'}. The self (server) app was NOT touched -- it remains as-is and updatable via hub_update_app."
            ]
        }
    }

    mcpLog("info", "developer-mode", "hub_update_package: ref=${ref} bundles=${bundleResults.size()} apps=${appResults.size()} repaired")
    def result = [
        success: true, ref: ref, includes: includeTokens, bundles: bundleResults, apps: appResults,
        message: "Package repaired to ref ${ref}: ${bundleResults.size()} bundle(s) + ${appResults.size()} app(s) deployed (self app last)."
    ]
    // Whenever a bundle leg FELL BACK from the per-ref artifact, say so. The wording keys
    // on what was actually installed: "manifest-current" = the manifest's bundle-artifacts
    // branches/main zip (exactly what HPM users install; only wrong if this ref's libraries
    // differ from current main); "committed-at-ref" = a legacy ref's in-tree zip (only
    // wrong if the ref changed library code relative to its base).
    if (bundleResults.any { it.source == "manifest-current" }) {
        if (ref == "main") {
            result.bundleFreshnessWarning = "The per-ref artifact probe failed, so the bundle leg installed the manifest's branches/main zip directly -- for ref=main that is the correct, current bundle (the same bytes HPM users install). Noted only because the probe failing may indicate a transient raw.githubusercontent issue."
            mcpLog("warn", "developer-mode", "hub_update_package: ref=main artifact probe failed; installed the manifest's branches/main zip directly (correct bytes for main)")
        } else {
            result.bundleFreshnessWarning = "Ref '${ref}': no bundle-artifacts zip was found for this ref, so the bundle leg installed CURRENT MAIN's bundle (the manifest's branches/main zip). That is only correct if this ref's libraries match current main. If this ref changed library code, push it to this repo (publish-bundle-artifact stores a fresh zip per push, keyed by branch and full SHA) and re-run."
            mcpLog("warn", "developer-mode", "hub_update_package: non-main ref '${ref}' fell back to current main's bundle -- wrong if the ref changed library code")
        }
    } else if (bundleResults.any { it.source == "committed-at-ref" }) {
        result.bundleFreshnessWarning = "Ref '${ref}' (legacy manifest): no bundle-artifacts zip was found for this ref, so the bundle leg installed the zip COMMITTED at the ref. That is only stale if this ref CHANGED library code without rebuilding the zip."
        mcpLog("warn", "developer-mode", "hub_update_package: legacy ref '${ref}' fell back to the committed bundle zip -- stale if the ref changed library code")
    }
    return result
}

def _getAllToolDefinitions_partSelfAdmin() {
    return [
        [
            name: "hub_update_mcp_settings",
            description: "Update one or more of the MCP rule app's own settings (toggles, log levels, tuning) in place — self-administer the app without the Hubitat UI. Gated on enableDeveloperMode + the Write master + confirm=true + a recent backup; every successful write is logged at WARN for audit. Changing an enable* toggle, useGateways, or publishOutputSchemas reshapes tools/list, so MCP clients may need to reconnect to refresh cached schemas.",
            inputSchema: [
                type: "object",
                properties: [
                    settings: [type: "object", description: "Map of setting key → new value (e.g. {\"mcpLogLevel\":\"warn\",\"enableCustomRuleEngine\":true}). Allowlisted keys: mcpLogLevel, debugLogging, maxCapturedStates, loopGuardMax, loopGuardWindowSec, enableRead, enableCustomRuleEngine, useGateways, publishOutputSchemas, enableMandatoryBPS — any other key is rejected.[[FLAT_TRIM]] Deliberately NOT allowlisted: enableWrite (would disable this tool's own write path mid-session), enableDeveloperMode (lockout protection — must stay UI-only to disable), selectedDevices (different wire format, has its own tool), disabled_tools/disabled_gateways (could self-disable this tool).[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to confirm the operation"]
                ],
                required: ["settings", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the settings were updated"],
                    updated: [type: "object", description: "Map of applied setting key → coerced new value"],
                    message: [type: "string", description: "Human-readable result, including reconnect note"]
                ],
                required: ["success", "updated", "message"]
            ]
        ],
        [
            name: "hub_update_package",
            description: """Developer Mode self-deploy: full HPM-repair of the MCP package at a git ref in one call -- OVERRIDES whatever is installed, the same way Hubitat Package Manager's Repair does, but anchored to packageManifest.json AT `ref` so an UNMERGED PR installs (HPM repair only reads the published manifest).[[FLAT_TRIM]]

Deploys every declared library bundle + app from the manifest at `ref`, saving the running self app LAST (its recompile can drop the response, #237). Does NOT touch app instances, undeclared drivers, or anything outside this package's manifest.

Brick-safe: if ANYTHING before the self app save fails (app/manifest fetch, an unresolved app class, a bundle install, a non-self app), it aborts BEFORE touching the self app -- the running server is left exactly as-is and still updatable via hub_update_app, the always-available escape hatch. Self-modification is gated by this tool's own enableDeveloperMode check (it deploys by Apps Code CLASS id, so hub_update_app's instance-id self-update guard does not fire here).[[/FLAT_TRIM]]

Gated on enableDeveloperMode (the tool is hidden from tools/list when Developer Mode is off) + the Write master + confirm=true + a recent backup. Use dryRun=true to fetch + parse + plan with ZERO writes (no confirm/backup needed) and see exactly which bundles and apps would deploy.""",
            inputSchema: [
                type: "object",
                properties: [
                    ref: [type: "string", description: "Branch, tag, or commit SHA to deploy (e.g. 'main' or a PR head SHA). Source is fetched from the canonical repo raw base at this ref."],
                    dryRun: [type: "boolean", description: "OPTIONAL. When true, fetch + parse + resolve + report the deploy plan with NO writes (skips the confirm/backup gate). Default false."],
                    baseUrl: [type: "string", description: "OPTIONAL raw-source base override (no trailing slash); URLs are built as <baseUrl>/<ref>/<path>. Defaults to the canonical repo. Use for forks / CI branches on a different remote."],
                    confirm: [type: "boolean", description: "REQUIRED for a real deploy (omit for dryRun). Must be true; confirms a recent backup exists and the user approved the self-deploy."]
                ],
                required: ["ref"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "True when the deploy (or dry-run plan) completed; false on abort or app-update failure"],
                    ref: [type: "string", description: "The git ref deployed"],
                    dryRun: [type: "boolean", description: "True when this was a plan-only run (no writes)"],
                    aborted: [type: "boolean", description: "True when the deploy stopped before the self app save; the running server was left untouched"],
                    partial: [type: "boolean", description: "True when bundle(s) + other apps landed but the self app did not update -- its call threw (likely the self-update recompile dropped the response) OR returned a failure"],
                    abortReason: [type: "string", description: "Machine-readable abort cause (app_source_fetch_failed / manifest_fetch_failed / manifest_unparseable / bundle_location_unusable / bundle_required_but_undeclared / app_class_unresolved / bundle_install_failed / bundle_install_threw / app_update_failed / app_update_threw)"],
                    appUrl: [type: "string", description: "Raw URL the self app source was fetched from (for the #include coverage check)"],
                    includes: [type: "array", description: "Parsed #include tokens (namespace.Name) from the self app source", items: [type: "string"]],
                    plannedBundles: [type: "array", description: "dryRun: library bundles that would be installed (name, ref-anchored url)", items: [type: "object"]],
                    plannedApps: [type: "array", description: "dryRun: apps that would be deployed (name, namespace, classId, url, isSelf); self app last", items: [type: "object"]],
                    bundles: [type: "array", description: "Per-bundle install results (name, url, success, error)", items: [type: "object"]],
                    apps: [type: "array", description: "Per-app deploy results (name, namespace, classId, isSelf, success, app); self app last", items: [type: "object"]],
                    app: [type: "object", description: "The hub_update_app result for the self app leg (present only when the self app update RETURNED a failure, not on the threw/dropped-response path)"],
                    message: [type: "string", description: "Human-readable summary"],
                    error: [type: "string", description: "Failure detail; present on abort / failure"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _idempotentWriteToolNames_partSelfAdmin() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // MCP self-admin + logging
        "hub_update_mcp_settings",
        // Developer Mode self-deploy (full repair to a ref converges; retrying
        // after a dropped response is its designed recovery path)
        "hub_update_package"
    ]
}

def _openWorldToolNames_partSelfAdmin() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        "hub_update_package"
    ]
}

def _developerModeOnlyToolNames_partSelfAdmin() {
    // Developer-Mode-only tools in this library (catalog-hidden until the toggle is on) --
    // contributed to the app's getDeveloperModeOnlyToolNames() aggregator.
    return [
        "hub_update_package"
    ]
}

def _toolDisplayMeta_partSelfAdmin() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // MCP self-administration (Developer Mode)
        hub_update_mcp_settings: [title: "Update MCP Settings", summary: "Update the MCP app's own settings (Developer Mode)."],
        hub_update_package: [title: "Deploy MCP Package", summary: "Full repair-install of the MCP package at a git ref (Developer Mode)."]
    ]
}
