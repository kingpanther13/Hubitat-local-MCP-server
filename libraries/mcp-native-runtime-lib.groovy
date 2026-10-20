library(name: "McpNativeRuntimeLib", namespace: "mcp", author: "kingpanther13", description: "Native rule runtime primitives.")

def _nrHandle(Long ruleId) {
    if (ruleId == null || ruleId <= 0L) throw new IllegalArgumentException('A positive rule ID is required')
    def target = getAppByAppId(ruleId)
    if (target == null || target.id?.toString() != ruleId.toString()) throw new IllegalArgumentException('Rule not found')
    def type = target.appType
    if (type?.namespace != 'hubitat' || !(type?.name in ['Rule-5.1', 'Button Rule-5.1'])) {
        throw new IllegalArgumentException('Target must be a native Rule-5.1 or Button Rule-5.1')
    }
    def parentId = target.parentAppId
    if (parentId == null || !parentId.toString().isLong() || parentId.toString().toLong() <= 0L) {
        throw new IllegalArgumentException('Native rule has no valid parent')
    }
    def handle = getChildAppsByParentId(parentId.toString().toLong())?.find { child -> child.id?.toString() == ruleId.toString() }
    if (handle == null) throw new IllegalArgumentException('Native rule is not a child of its recorded parent')
    handle
}

Map _nrState(Long ruleId) {
    def fresh = _nrHandle(ruleId).getState()
    if (!(fresh instanceof Map)) throw new IllegalStateException('Native state is unavailable')
    [:] + fresh
}

Map _nrStatePatch(Long ruleId, Map put, List remove = []) {
    if (put == null || remove == null || put.keySet().any { !(it instanceof String) || !it } || remove.any { !(it instanceof String) || !it }) {
        throw new IllegalArgumentException('State changes require named keys')
    }
    if (remove.any { put.containsKey(it) }) throw new IllegalArgumentException('A state key cannot be assigned and removed together')
    def handle = _nrHandle(ruleId)
    def fresh = handle.getState()
    if (!(fresh instanceof Map)) throw new IllegalStateException('Native state is unavailable')
    def next = [:] + fresh
    remove.each { key -> next.remove(key) }
    next.putAll(put)
    handle.setState(next)
    def result = handle.saveState()
    [verificationNeeded: true, result: result, assigned: put.keySet().toList(), removed: remove.toList()]
}

Map _nrSettings(Long ruleId, List names) {
    if (names == null || names.any { !(it instanceof String) || !it }) throw new IllegalArgumentException('Setting names are required')
    def handle = _nrHandle(ruleId)
    def rows = [:]
    names.each { name -> rows[name] = [type: handle.getSettingType(name), value: _nrSettingValue(handle.getSetting(name))] }
    rows
}

private Object _nrSettingValue(Object value) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value
    if (value instanceof List) return value.collect { item -> _nrSettingValue(item) }
    def id = value.id
    if (id != null) return id.toString()
    throw new IllegalStateException('Unsupported native setting value')
}

private Object _nrComparable(String type, Object value) {
    if (value instanceof List) return value.collect { item -> _nrComparable(type, item) }.sort()
    if (value == null) return null
    if (type == 'number' || type == 'decimal') return value.toString().isBigDecimal() ? new BigDecimal(value.toString()).stripTrailingZeros().toPlainString() : value.toString()
    if (type == 'bool') return value.toString().toLowerCase()
    value.toString()
}

Map _nrWriteSettings(Long ruleId, List rows) {
    if (rows == null) throw new IllegalArgumentException('Setting rows are required')
    def names = []
    rows.each { row ->
        if (!(row instanceof Map) || !(row.name instanceof String) || !row.name || !(row.type instanceof String) || !row.containsKey('value')) {
            throw new IllegalArgumentException('Each setting requires name, type and value')
        }
        if (row.keySet().any { !(it in ['name', 'type', 'value']) } || row.name in names) throw new IllegalArgumentException('Unsupported or repeated setting fields')
        def device = row.type == 'capability' || row.type.startsWith('capability.')
        def enumType = row.type in ['enum', 'mode']
        if (!(row.type in ['text', 'textarea', 'password', 'enum', 'mode', 'bool', 'number', 'decimal', 'time']) && !device) throw new IllegalArgumentException('Unsupported native setting type')
        if (row.value == null) throw new IllegalArgumentException('Null setting values are unsupported; they do not reliably clear native settings')
        if (row.value instanceof List) {
            if (!device && !enumType) throw new IllegalArgumentException('Lists require enum, mode or capability type')
            if (row.value.any { !(it instanceof String || it instanceof Number || it instanceof Boolean) }) throw new IllegalArgumentException('Setting lists require scalar values')
        } else if (!(row.value instanceof String || row.value instanceof Number || row.value instanceof Boolean)) {
            throw new IllegalArgumentException('Unsupported native setting value')
        }
        if (device) {
            def ids = row.value instanceof List ? row.value : [row.value]
            if (ids.any { !it.toString().isLong() || it.toString().toLong() <= 0L }) throw new IllegalArgumentException('Capability settings require positive device IDs')
        }
        names << row.name
    }
    def handle = _nrHandle(ruleId)
    def incompatible = rows.findAll { row ->
        def existing = handle.getSettingType(row.name)
        existing != null && existing.toString() != row.type
    }.collect { row -> row.name }
    if (incompatible) return [success: false, partial: false, verificationNeeded: true, attempted: [], rejected: incompatible, reason: 'existing_type_mismatch']
    def attempted = []
    def results = []
    try {
        for (row in rows) {
            attempted << row.name
            results << handle.updateSetting(row.name, [type: row.type, value: row.value])
        }
    } catch (Exception failure) {
        return [success: false, verificationNeeded: true, settingsVerified: false, partial: !attempted.empty, attempted: attempted,
                results: results, error: failure.message?.toString()]
    }
    try {
        def actual = _nrSettings(ruleId, names)
        def rejected = rows.findAll { row ->
            actual[row.name].type?.toString() != row.type ||
                _nrComparable(row.type, actual[row.name].value) != _nrComparable(row.type, row.value)
        }.collect { row -> row.name }
        return [success: rejected.empty, verificationNeeded: true, settingsVerified: rejected.empty,
                partial: !rejected.empty && !attempted.empty, attempted: attempted, results: results,
                rejected: rejected, settings: actual]
    } catch (Exception failure) {
        return [success: false, verificationNeeded: true, settingsVerified: false, partial: !attempted.empty,
                attempted: attempted, results: results, error: failure.message?.toString()]
    }
}

Map _nrPage(Long ruleId, String page, Map params = [:]) {
    if (!(page in ['mainPage', 'selectActions', 'doActPage', 'STPage', 'selectTriggers', 'selectConditions', 'localVar'])) throw new IllegalArgumentException('Unsupported native page')
    if (params == null || (params && !(page in ['selectActions', 'STPage', 'selectConditions']))) throw new IllegalArgumentException('This page does not accept parameters')
    def handle = _nrHandle(ruleId)
    def result
    switch (page) {
        case 'mainPage': result = handle.mainPage(); break
        case 'selectActions': result = handle.selectActions(params); break
        case 'doActPage': result = handle.doActPage(); break
        case 'STPage': result = handle.STPage(params); break
        case 'selectTriggers': result = handle.selectTriggers(); break
        case 'selectConditions': result = handle.selectConditions(params); break
        case 'localVar': result = handle.localVar(); break
    }
    [verificationNeeded: true, result: result]
}

Map _nrButton(Long ruleId, String name) {
    if (!name) throw new IllegalArgumentException('A native button name is required')
    def result = _nrHandle(ruleId).appButtonHandler(name)
    // Native handlers may consume errors internally; recipes must verify resulting structure.
    [verificationNeeded: true, result: result]
}
