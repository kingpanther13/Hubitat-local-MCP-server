definition(name: "Issue415 Exact Branch Proof", namespace: "issue415-owned-proof", author: "Codex", description: "Inert source-copy proof; no native HTTP, devices, rules, or schedules.", category: "Convenience", singleInstance: false)
preferences { page(name: "proofPage") }
def installed() {}
def updated() {}

// These two stubs capture the helper's map and form envelope without serializing
// settings to Hubitat or issuing any HTTP request. They do not prove native UI reachability.
private Map _rmBuildSettingsBody(Integer ignoredId, Map values, Map ignoredSchema) {
    return [capturedValues: new LinkedHashMap(values)]
}
private Map hubInternalPostForm(String path, Map body) {
    if (path != "/installedapp/update/json") throw new IllegalArgumentException("Unexpected path")
    return [status: 200, capturedBody: body]
}
def mcpLog(a, b, c) {}

private void recordCase(List results, String label, String key, boolean expectedFailure, Closure operation) {
    try {
        def result = operation.call()
        results << [label:label, key:key, expectedFailure:expectedFailure, passed:!expectedFailure, result:result]
    } catch (Exception e) {
        def expectedSandbox = e instanceof SecurityException && e.message?.contains("Subscript property '")
        results << [label:label, key:key, expectedFailure:expectedFailure, passed:expectedFailure && expectedSandbox, error:e.toString()]
    }
}

def proofPage() {
    def results = []
    for (key in ["ordinary", "fields", "class", "metaClass", "Fields", "getClass"]) {
        for (variant in ["Before", "After"]) {
            boolean expectedFailure = variant == "Before" && key in ["fields", "class", "metaClass"]
            def options = new LinkedHashMap()
            options.put(key, "<b>Label</b> &amp; text")
            options.put("falseValue", false)
            options.put("zeroValue", 0)
            options.put("nullValue", null)
            options.put("listValue", [false, 0, null])
            recordCase(results, "row6-" + variant, key, expectedFailure) {
                def actual = variant == "Before" ? stripOptionsHtmlBefore(options) : stripOptionsHtmlAfter(options)
                def expected = new LinkedHashMap(options)
                expected.put(key, "Label & text")
                if (actual != expected) throw new IllegalStateException("Options value or shape mismatch")
                return actual
            }
            for (kind in ["storedFalse", "storedZero", "storedNull", "storedList", "button", "missing", "override"]) {
                recordCase(results, "fullForm-" + kind + "-" + variant, key, expectedFailure) {
                    def schema = new LinkedHashMap()
                    schema.put(key, [type:kind == "button" ? "button" : "text"])
                    def current = new LinkedHashMap()
                    def extra = new LinkedHashMap()
                    def expectedValue = ""
                    if (kind == "storedFalse") expectedValue = false
                    if (kind == "storedZero") expectedValue = 0
                    if (kind == "storedNull") expectedValue = null
                    if (kind == "storedList") expectedValue = [false, 0, null, [fields:"nested"]]
                    if (kind.startsWith("stored")) current.put(key, expectedValue)
                    if (kind == "override") { extra.put(key, false); expectedValue = false }
                    def cfg = [app:[label:"Synthetic inert form", version:7]]
                    def actual = variant == "Before" ? _rmSubmitFullPageFormBefore(1, "selectActions", cfg, schema, current, extra) : _rmSubmitFullPageFormAfter(1, "selectActions", cfg, schema, current, extra)
                    def captured = actual.capturedBody.capturedValues
                    if (!captured.containsKey(key) || captured.get(key) != expectedValue) throw new IllegalStateException("Stored value or membership mismatch")
                    def expectedBlanked = kind == "missing" ? [key] : []
                    if ((actual.blankedInputs ?: []) != expectedBlanked) throw new IllegalStateException("blankedInputs mismatch")
                    if (actual.capturedBody.formAction != "update" || actual.capturedBody.version != "7") throw new IllegalStateException("Envelope mismatch")
                    return [values:captured, blankedInputs:(actual.blankedInputs ?: []), envelopePreserved:true]
                }
            }
        }
    }

    for (key in ["ordinary", "fields", "class", "metaClass", "Fields", "getClass"]) {
        recordCase(results, "dotRead", key, false) { requireFalse(dotRead(key)) }
        recordCase(results, "dotWrite", key, false) { requireFalse(dotWrite(key)) }
        recordCase(results, "typedDotRead", key, false) { requireFalse(typedDotRead(key)) }
        recordCase(results, "typedDotWrite", key, false) { requireFalse(typedDotWrite(key)) }
    }
    dynamicPage(name:"proofPage",title:"Exact helper branch proof",install:true,uninstall:true) {
        section("Results") { paragraph groovy.json.JsonOutput.toJson([count:results.size(), passed:results.count { it.passed }, cases:results]) }
    }
}

private stripOptionsHtmlBefore(options) {
    if (options instanceof List) {
        def out = []
        for (entry in options) {
            if (entry instanceof Map) {
                def cleaned = [:]
                entry.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
                out << cleaned
            } else {
                out << entry
            }
        }
        return out
    }
    if (options instanceof Map) {
        def cleaned = [:]
        options.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
        return cleaned
    }
    return options
}

private stripOptionsHtmlAfter(options) {
    if (options instanceof List) {
        def out = []
        for (entry in options) {
            if (entry instanceof Map) {
                def cleaned = [:]
                entry.each { k, v -> cleaned.put(k, (v instanceof String) ? stripAppConfigHtml(v) : v) }
                out << cleaned
            } else {
                out << entry
            }
        }
        return out
    }
    if (options instanceof Map) {
        def cleaned = [:]
        options.each { k, v -> cleaned.put(k, (v instanceof String) ? stripAppConfigHtml(v) : v) }
        return cleaned
    }
    return options
}

private Map _rmSubmitFullPageFormBefore(Integer appId, String pageName, Map cfg, Map schema, Map currentSettings, Map extraSettings) {
    // Re-emit every current page input so the wholesale-replace submit does not
    // drop untouched fields, then overlay the inputs being changed. Inputs are
    // enumerated from the page schema; each takes its value from currentSettings.
    def fullMap = [:]
    def blankedInputs = []
    schema?.each { name, meta ->
        if (currentSettings?.containsKey(name)) {
            fullMap[name] = currentSettings[name]
        } else if (meta?.type == 'button') {
            // Buttons carry no persisted value; the UI serializes them empty.
            fullMap[name] = ""
        } else {
            // Non-button input absent from the page settings map. If it is also
            // not among the inputs being written this submit (extraSettings), it
            // would be silently blanked -- surface it so a future caller on a
            // page where preservation matters sees the gap. Inputs supplied via
            // extraSettings are being set deliberately (not blanked), so they do
            // not warn. (Harmless for the trashActs delete path either way.)
            if (!extraSettings?.containsKey(name)) {
                blankedInputs << name
                mcpLog("warn", "rm-native", "_rmSubmitFullPageFormBefore: page input '${name}' (type=${meta?.type}) on ${pageName} for app ${appId} is absent from configPage settings -- submitting empty; if this field needed preserving the full-form submit may blank it")
            }
            fullMap[name] = ""
        }
    }
    extraSettings?.each { k, v -> fullMap[k] = v }

    def body = _rmBuildSettingsBody(appId, fullMap, schema)

    // Form-action envelope -- the part that makes RM run the submitOnChange
    // handler during the page re-render instead of only persisting the value.
    body.formAction = "update"
    body.currentPage = pageName
    body.pageBreadcrumbs = '["mainPage"]'
    // appTypeId / appTypeName are empty for Rule Machine (the native UI sends
    // them blank); emitted explicitly so the body matches the wire capture.
    body.appTypeId = ""
    body.appTypeName = ""
    def label = cfg?.app?.label
    body.paramsForPage = groovy.json.JsonOutput.toJson([label: (label != null ? label.toString() : "")])
    // version is RM's concurrent-edit token; replay the exact one the page render
    // would send. Missing it can make the hub reject the submit as a stale edit.
    def v = cfg?.app?.version
    if (v != null) body.version = v.toString()

    def resp = hubInternalPostForm("/installedapp/update/json", body)
    if (resp?.status != null && resp.status >= 400) {
        // Surface a truncated body preview so operators see WHY RM rejected the
        // submit (stale version token, auth, malformed envelope, etc.) instead
        // of just a bare status code.
        def bodyPreview = resp?.data?.toString()?.take(200)
        throw new IllegalStateException("Full-form submit on ${pageName} for app ${appId} failed: status=${resp.status}${bodyPreview ? "; body=" + bodyPreview : ""}. The submit was rejected so nothing was committed (a 4xx is usually a stale version token -- re-fetch via hub_get_app_config(appId=${appId}) and retry). The page may be left in trash-confirmation mode; on this hard-fail path the tool backs it out automatically via cancelTrash. Do NOT treat this as a partial delete.")
    }
    // Surface any non-button inputs the wholesale-replace blanked (absent from
    // currentSettings AND not in extraSettings) so a caller can refuse a
    // silently-blanked write. Empty/absent on the trashActs delete path, where
    // nothing load-bearing is blanked.
    if (blankedInputs && resp instanceof Map) resp.blankedInputs = blankedInputs
    return resp
}

private Map _rmSubmitFullPageFormAfter(Integer appId, String pageName, Map cfg, Map schema, Map currentSettings, Map extraSettings) {
    // Re-emit every current page input so the wholesale-replace submit does not
    // drop untouched fields, then overlay the inputs being changed. Inputs are
    // enumerated from the page schema; each takes its value from currentSettings.
    def fullMap = [:]
    def blankedInputs = []
    schema?.each { name, meta ->
        if (currentSettings?.containsKey(name)) {
            fullMap.put(name, currentSettings.get(name))
        } else if (meta?.type == 'button') {
            // Buttons carry no persisted value; the UI serializes them empty.
            fullMap.put(name, "")
        } else {
            // Non-button input absent from the page settings map. If it is also
            // not among the inputs being written this submit (extraSettings), it
            // would be silently blanked -- surface it so a future caller on a
            // page where preservation matters sees the gap. Inputs supplied via
            // extraSettings are being set deliberately (not blanked), so they do
            // not warn. (Harmless for the trashActs delete path either way.)
            if (!extraSettings?.containsKey(name)) {
                blankedInputs << name
                mcpLog("warn", "rm-native", "_rmSubmitFullPageFormAfter: page input '${name}' (type=${meta?.type}) on ${pageName} for app ${appId} is absent from configPage settings -- submitting empty; if this field needed preserving the full-form submit may blank it")
            }
            fullMap.put(name, "")
        }
    }
    extraSettings?.each { k, v -> fullMap.put(k, v) }

    def body = _rmBuildSettingsBody(appId, fullMap, schema)

    // Form-action envelope -- the part that makes RM run the submitOnChange
    // handler during the page re-render instead of only persisting the value.
    body.formAction = "update"
    body.currentPage = pageName
    body.pageBreadcrumbs = '["mainPage"]'
    // appTypeId / appTypeName are empty for Rule Machine (the native UI sends
    // them blank); emitted explicitly so the body matches the wire capture.
    body.appTypeId = ""
    body.appTypeName = ""
    def label = cfg?.app?.label
    body.paramsForPage = groovy.json.JsonOutput.toJson([label: (label != null ? label.toString() : "")])
    // version is RM's concurrent-edit token; replay the exact one the page render
    // would send. Missing it can make the hub reject the submit as a stale edit.
    def v = cfg?.app?.version
    if (v != null) body.version = v.toString()

    def resp = hubInternalPostForm("/installedapp/update/json", body)
    if (resp?.status != null && resp.status >= 400) {
        // Surface a truncated body preview so operators see WHY RM rejected the
        // submit (stale version token, auth, malformed envelope, etc.) instead
        // of just a bare status code.
        def bodyPreview = resp?.data?.toString()?.take(200)
        throw new IllegalStateException("Full-form submit on ${pageName} for app ${appId} failed: status=${resp.status}${bodyPreview ? "; body=" + bodyPreview : ""}. The submit was rejected so nothing was committed (a 4xx is usually a stale version token -- re-fetch via hub_get_app_config(appId=${appId}) and retry). The page may be left in trash-confirmation mode; on this hard-fail path the tool backs it out automatically via cancelTrash. Do NOT treat this as a partial delete.")
    }
    // Surface any non-button inputs the wholesale-replace blanked (absent from
    // currentSettings AND not in extraSettings) so a caller can refuse a
    // silently-blanked write. Empty/absent on the trashActs delete path, where
    // nothing load-bearing is blanked.
    if (blankedInputs && resp instanceof Map) resp.blankedInputs = blankedInputs
    return resp
}

private String stripAppConfigHtml(value) {
    if (value == null) return null
    def s = value.toString()
    // Strip HTML tags, then any leftover CSS-rule / inline-script bodies that
    // Hubitat embeds via <style>/<script>: the tags strip above but the
    // "selector{...}" / "fn(){...}" bodies remain mashed into the text (e.g. the
    // Local Variables `lvTable` page). Only blocks containing ; or : inside the
    // braces are removed, so prose like "{x}" is preserved.
    if (s.contains("<")) {
        s = s.replaceAll(/<[^>]+>/, "").replaceAll(/[^{}]*\{[^{}]*[;:][^{}]*\}/, "")
    }
    // Decode the common HTML entities Hubitat escapes user-typed names with: a
    // rule the user named "Heat On <67" is stored (and listed) as "Heat On &lt;67".
    // Decode &amp; LAST so a single-encoded "&lt;" resolves correctly.
    if (s.contains("&")) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
             .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
             .replace("&amp;", "&")
    }
    return s.trim()
}


private def dotRead(key) {
    def data = [:]
    data.put(key, false)
    return data?."${key}"
}
private def dotWrite(key) {
    def data = [:]
    data."${key}" = false
    return data.get(key)
}
private def typedDotRead(key) {
    Map data = [:]
    data.put(key, false)
    return data?."${key}"
}
private def typedDotWrite(key) {
    Map data = [:]
    data."${key}" = false
    return data.get(key)
}

private Boolean requireFalse(value) {
    if (value != false) throw new IllegalStateException('Expected the stored false value')
    return value
}
