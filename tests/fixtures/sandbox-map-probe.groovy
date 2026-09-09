definition(name: "Codex415 Map Probe 0909", namespace: "codex415", author: "Codex", description: "Inert temporary sandbox diagnostic", category: "Convenience", singleInstance: true)
preferences { page(name: "mainPage") }
def installed() {}
def updated() {}
def mainPage() {
    def results = []
    ["ordinary", "fields", "getClass", "class", "metaClass"].each { key ->
        ["read", "write", "get", "put", "eachWrite"].each { operation ->
            def row = [key: key, operation: operation]
            try {
                Map target = new LinkedHashMap()
                target.put(key, false)
                def result
                if (operation == "read") result = target[key]
                if (operation == "write") { target[key] = 0; result = target.get(key) }
                if (operation == "get") result = target.get(key)
                if (operation == "put") { target.put(key, 0); result = target.get(key) }
                if (operation == "eachWrite") {
                    def copy = [:]
                    target.each { k, v -> copy[k] = v }
                    result = copy.get(key)
                }
                row.put("success", true)
                row.put("result", result)
            } catch (Exception e) {
                row.put("success", false)
                row.put("error", e.toString())
            }
            results.add(row)
        }
    }
    dynamicPage(name: "mainPage", title: "Codex415 inert probes", install: true, uninstall: true) {
        section("Results") { paragraph groovy.json.JsonOutput.toJson(results) }
    }
}

