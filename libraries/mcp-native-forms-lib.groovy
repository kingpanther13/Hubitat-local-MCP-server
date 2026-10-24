library(name: "McpNativeFormsLib", namespace: "mcp", author: "kingpanther13", description: "Native classic app form inspection and submission.")

@groovy.transform.Field static final Map NATIVE_FORM_CONTEXTS = new java.util.concurrent.ConcurrentHashMap()
@groovy.transform.Field static final Map NATIVE_FORM_BUSY = new java.util.concurrent.ConcurrentHashMap()

private String _nfEncode(Object value) { java.net.URLEncoder.encode(value == null ? '' : value.toString(), 'UTF-8') }
private String _nfBody(Map values) { values.collect { key, value -> "${_nfEncode(key)}=${_nfEncode(value)}" }.join('&') }
private boolean _nfTrue(Object value) { value == true || value == 'true' }
private String _nfText(Object value) { _nfEntities(value?.toString()?.replaceAll(/(?s)<[^>]*>/, '') ?: '') }
private String _nfEntities(String value) {
    value.replace('&quot;', '"').replace('&#39;', "'").replace('&apos;', "'").replace('&lt;', '<').replace('&gt;', '>').replace('&nbsp;', ' ').replace('&amp;', '&')
}
private String _nfKey(Long id) { "${app.id}:${id}".toString() }
private String _nfCallerKey() { "${app.id}:caller:${callerClient == null ? 'main' : 'client:' + callerClient.id}".toString() }

private Map _nfJson(Object value) {
    def parsed
    try { parsed = value instanceof Map ? value : new groovy.json.JsonSlurper().parseText(value?.toString() ?: '') }
    catch (Exception ignored) { throw new IllegalStateException('Native form returned unreadable JSON; inspect the app before retrying') }
    if (!(parsed instanceof Map)) throw new IllegalStateException('Native form returned an invalid response')
    if (parsed.status && parsed.status != 'success') throw new IllegalStateException('Native form rejected the request')
    if (parsed.error || parsed.errorMessage || parsed.configPage?.error) throw new IllegalStateException('Native form reported an error; inspect the native editor and logs')
    parsed
}

private Map _nfPost(String path, Map values) {
    def response = hubInternalPostFormRaw(path, _nfBody(values))
    if (response?.status != 200) throw new IllegalStateException('Native form request did not return HTTP 200; inspect the app before retrying')
    _nfJson(response.data)
}

private List _nfOptions(Object options) {
    if (options instanceof Map) return options.collect { key, value -> [value: key.toString(), label: _nfText(value)] }
    if (options instanceof List) return options.collectMany { item ->
        item instanceof Map ? _nfOptions(item) : [[value: item.toString(), label: _nfText(item)]]
    }
    null
}

private Map _nfAttrs(String html) {
    Map attrs = [:]
    def matches = html =~ /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/
    while (matches.find()) attrs.put(matches.group(1).toLowerCase(), _nfEntities(matches.group(2) != null ? matches.group(2) : matches.group(3)))
    attrs
}

private Map _nfLocalPath(String path) {
    def match = path =~ /^\/installedapp\/configure\/([1-9][0-9]*)\/([A-Za-z_][A-Za-z0-9_.-]*)\?resetScroll=true$/
    match.matches() && match.group(1).isLong() ? [app_id: match.group(1).toLong(), page: match.group(2)] : null
}

private Map _nfLocalNavigation(String html) {
    def match = html =~ /(?s)^\s*<script>\s*\{\s*window\.open\('\/installedapp\/configure\/([1-9][0-9]*)\/([A-Za-z_][A-Za-z0-9_.-]*)\?resetScroll=true',\s*'_self'\)\s*;?\s*\}\s*<\/script>\s*$/
    match.matches() && match.group(1).isLong() ? [app_id: match.group(1).toLong(), page: match.group(2)] : null
}

private void _nfNavigationTargets(Object node, List targets) {
    if (node instanceof List) { node.each { _nfNavigationTargets(it, targets) }; return }
    if (!(node instanceof Map)) return
    if (node.element == 'paragraph') ['title', 'description', 'text'].each { key ->
        def value = node[key]
        Map target = value instanceof String ? _nfLocalNavigation(value) : null
        if (target) targets << target
    }
    ['sections', 'input', 'body', 'children'].each { key ->
        if (node[key] instanceof Map || node[key] instanceof List) _nfNavigationTargets(node[key], targets)
    }
}

private Map _nfNavigation(Long sourceId, Map model) {
    List targets = []
    _nfNavigationTargets(model.configPage, targets)
    if (!targets) return null
    if (model.app?.id?.toString() != sourceId.toString()) throw new IllegalStateException('Native navigation response does not identify the requested app')
    if (targets.unique().size() != 1) throw new IllegalStateException('Native form returned ambiguous app navigation')
    _nfVerifyNavigation(sourceId, targets[0])
}

private Map _nfVerifyNavigation(Long sourceId, Map target) {
    def nativeApp = getAppByAppId(target.app_id as Long)
    if (nativeApp?.id?.toString() != target.app_id.toString() || nativeApp.disabled == true ||
        (target.app_id != sourceId && nativeApp.parentAppId?.toString() != sourceId.toString())) {
        throw new IllegalStateException('Native navigation target must be the enabled app or its direct child')
    }
    _requireUnprotectedAppMutation(target.app_id, 'open native form navigation')
    target
}

private void _nfHtml(String html, Map controls, List unsupported) {
    // These hidden inputs and callback are the native buttonLink contract; other scripts are never evaluated.
    def inputs = html =~ /(?is)<input\b[^>]*>/
    List buttons = []
    while (inputs.find()) {
        Map attrs = _nfAttrs(inputs.group())
        if (attrs.type == 'hidden' && attrs.name?.endsWith('.type') && attrs.value == 'button') {
            buttons << [name: attrs.name[0..-6], end: inputs.end()]
        } else if (attrs.type != 'hidden') unsupported << 'Custom HTML input'
    }
    buttons.eachWithIndex { button, index ->
        String fragment = html.substring(button.end as int, index + 1 < buttons.size() ? buttons[index + 1].end as int : html.length())
        def click = fragment =~ /(?is)<(a|div|button)\b([^>]*\bonclick\s*=\s*(?:"[^"]*"|'[^']*')[^>]*)>(.*?)<\/\1>/
        boolean found = false
        while (click.find() && !found) {
            Map attrs = _nfAttrs(click.group(2))
            boolean known = attrs.onclick?.trim() ==~ /buttonClick\(this\)\s*;?\s*(?:return false;?)?/
            def hidden = fragment =~ /(?is)<input\b[^>]*>/
            boolean paired = false
            while (hidden.find()) {
                Map input = _nfAttrs(hidden.group())
                if (input.type == 'hidden' && input.name == "settings[${button.name}]") paired = true
            }
            if (known && paired) {
                Map control = [name: button.name, kind: 'button', title: attrs['aria-label'] ?: attrs.title ?: _nfText(click.group(3)), disabled: attrs.containsKey('disabled') || (click.group(2) =~ /(?i)(?:^|\s)disabled(?:\s|$)/).find(),
                               stateAttribute: attrs['data-stateattribute'], submit_on_change: (attrs.get('class') ?: '').tokenize().contains('submitOnChange')]
                if (attrs['data-confirm']) control.confirmation = attrs['data-confirm']
                _nfControl(controls, control)
                found = true
            }
        }
        if (!found) unsupported << "Unrecognized HTML button: ${button.name}"
    }
    if (html =~ /(?is)<script\b|\bonclick\s*=\s*["'](?!buttonClick\(this\))/) unsupported << 'Custom browser script'
    def links = html =~ /(?is)<a\b([^>]*)>(.*?)<\/a>/
    while (links.find()) {
        Map attrs = _nfAttrs(links.group(1))
        Map target = attrs.href instanceof String ? _nfLocalPath(attrs.href) : null
        if (target && !attrs.onclick) _nfControl(controls, [name: '_native_app', value: target.app_id.toString(),
            stateAttribute: target.page, page: target.page, kind: 'local_app', title: _nfText(links.group(2))])
        else if (attrs.href && attrs.href != '#') unsupported << 'HTML navigation link'
    }
}

private void _nfControl(Map controls, Map control) {
    String key = [control.name, control.value ?: '', control.stateAttribute ?: ''].collect { _nfEncode(it) }.join('|')
    control.control_id = key
    if (controls.containsKey(key) && controls[key] != control) throw new IllegalStateException('Native form contains ambiguous controls')
    controls.put(key, control)
}

private void _nfWalk(Object node, Map fields, Map controls, List messages, List unsupported, Map model, int position = 0) {
    if (node instanceof List) { node.eachWithIndex { item, index -> _nfWalk(item, fields, controls, messages, unsupported, model, index) }; return }
    if (!(node instanceof Map)) return
    if (node.element == 'input' && node.name && node.type) {
        String name = node.name.toString()
        String type = node.type.toString()
        if (type == 'button') {
            _nfControl(controls, [name: name, kind: 'button', title: _nfText(node.title), stateAttribute: node.state, disabled: _nfTrue(node.disabled),
                                  submit_on_change: _nfTrue(node.submitOnChange)])
        } else {
            boolean device = type == 'capability' || type.startsWith('capability.') || type.startsWith('device.')
            boolean supported = type in ['text', 'textarea', 'password', 'enum', 'mode', 'bool', 'number', 'decimal', 'time', 'email', 'phone'] || device
            Map field = [name: name, title: _nfText(node.title), type: type, multiple: _nfTrue(node.multiple), required: _nfTrue(node.required),
                         submit_on_change: _nfTrue(node.submitOnChange), supported: supported, device: device, disabled: _nfTrue(node.disabled)]
            ['range', 'min', 'max', 'step', 'description'].each { key -> if (node.containsKey(key)) field[key] = node[key] }
            def stored = model.settings instanceof Map ? model.settings.get(name) : null
            boolean exists = model.settings instanceof Map && model.settings.containsKey(name)
            def current = exists ? stored : node.defaultValue
            if (device && current instanceof Map) current = current.keySet().collect { it.toString() }
            if (device && !field.multiple && current instanceof List) current = current ? current[0] : null
            if (type == 'password') field.has_value = current != null && current != ''
            else { field.value = current; field.value_source = exists ? 'stored' : 'default' }
            // The page default the browser posts for an input with no stored value; a password's default is never posted.
            if (!exists && type != 'password' && current != null && current != '' && !(current instanceof Collection && current.isEmpty())) field.default_value = current
            field.present = exists && current != null && current != '' && (!(current instanceof Collection) || !current.empty)
            field.options = _nfOptions(type == 'mode' ? model.modeMap : node.options)
            if (device) {
                field.capability = type
                field.ignore = (node.ignoreDevices instanceof List ? node.ignoreDevices : (node.ignoreDevices?.toString()?.tokenize(',') ?: [])).collect { it.toString() }
            }
            if (fields.containsKey(name) && fields[name] != field) throw new IllegalStateException('Native form contains ambiguous fields')
            fields.put(name, field)
            if (!supported) unsupported << "Input type: ${type}"
        }
    } else if (node.element == 'app' && node.namespace == 'hubitat' && node.appName instanceof String && node.appName && node.name && node.appId?.toString() == model.app?.id?.toString()) {
        _nfControl(controls, [name: node.name.toString(), value: node.appName, kind: 'create_child', title: _nfText(node.title)])
    } else if (node.element == 'href') {
        if (node.page && !node.url) {
            String name = "_action_href_${node.name ?: 'name'}|${node.page}|${position + 1}"
            _nfControl(controls, [name: name, kind: 'href', title: _nfText(node.title), page: node.page.toString(),
                                  value: '', params: node.params instanceof Map ? node.params : null])
        } else unsupported << "Navigation requires a browser: ${_nfText(node.title)}"
    } else if (node.element && !(node.element in ['paragraph', 'section', 'label', 'image', 'input'])) {
        unsupported << "Native element: ${node.element}"
    }
    ['title', 'description', 'text'].each { key ->
        def value = node[key]
        if (value instanceof String && value.contains('<')) _nfHtml(value, controls, unsupported)
        if (node.element == 'paragraph' && value instanceof String && _nfText(value)) messages << _nfText(value)
    }
    ['sections', 'input', 'body', 'children'].each { key -> if (node[key] instanceof Map || node[key] instanceof List) _nfWalk(node[key], fields, controls, messages, unsupported, model) }
}

// from: the parent page of a sub-page opened directly. A direct render carries no breadcrumb trail,
// so the page's Done would have nowhere to return; the parent becomes the trail, as a link would make it.
private Map _nfContext(Long id, Map model, String from = null) {
    if (model.app?.id?.toString() != id.toString() || !(model.configPage instanceof Map)) throw new IllegalStateException('Native response does not identify the requested app and classic form')
    Map page = model.configPage
    Map fields = [:], controls = [:]
    List messages = [], unsupported = []
    _nfWalk(page, fields, controls, messages, unsupported, model)
    String trail = model.pageBreadcrumbs && model.pageBreadcrumbs != '%5B%5D' ? model.pageBreadcrumbs.toString() :
        (from ? _nfEncode(groovy.json.JsonOutput.toJson([from])) : '%5B%5D')
    if (page.install || model.doneButton) _nfControl(controls, [name: '_action_update', value: 'Done', kind: 'navigation', title: model.cancelButton ? 'Save' : 'Done'])
    else if (page.nextPage && !page.donePage) _nfControl(controls, [name: '_action_next', value: page.nextPageLabel ?: 'Next', kind: 'navigation', title: page.nextPageLabel ?: 'Next'])
    else if (page.nextPage && page.donePage) unsupported << 'Native _action_done browser navigation'
    else if (trail != '%5B%5D') _nfControl(controls, [name: '_action_previous', value: 'Done', kind: 'navigation', title: 'Done (previous page)'])
    if (model.cancelButton) _nfControl(controls, [name: '_action_update', value: 'Cancel', kind: 'navigation', title: 'Cancel'])
    if (model.removeButton) unsupported << 'Remove app: use the dedicated native-app deletion tool'
    if (model.defaultFields) unsupported << 'Default app label and mode controls'
    Map envelope = [id: id.toString(), currentPage: page.name ?: '', pageBreadcrumbs: trail]
    ['returnPath', 'referrer', 'url', 'formAction'].each { key -> if (model.containsKey(key)) envelope[key] = model[key] }
    ['nextPage', 'donePage'].each { key -> if (page[key]) envelope[key] = page[key] }
    if (page.returnPath != null) envelope.returnPath = page.returnPath
    if (page.returnPath == 'list') envelope.referrer = '/installedapp/list?section='
    if (model.app.containsKey('version')) envelope.version = model.app.version
    if (model.cancelButton != null) envelope._cancellable = model.cancelButton
    if (model.paramsForPage instanceof Map) envelope.paramsForPage = _nfEncode(groovy.json.JsonOutput.toJson(model.paramsForPage))
    Map context = [app_id: id, page: [name: page.name ?: '', title: _nfText(page.title)], fields: fields, controls: controls, messages: messages.unique(),
                   unsupported: unsupported.unique(), envelope: envelope, expires: now() + 300000L]
    if (fields.size() > 256 || controls.size() > 256) throw new IllegalStateException('Native form exceeds 256 fields or controls')
    context
}

private String _nfReloadPath(Long id, Map context) {
    def crumbs = new groovy.json.JsonSlurper().parseText(java.net.URLDecoder.decode(context.envelope.pageBreadcrumbs.toString(), 'UTF-8'))
    if (!(crumbs instanceof List)) throw new IllegalStateException('Native breadcrumbs are malformed')
    List pages = crumbs + (context.page.name ? [context.page.name] : [])
    if (pages.any { !(it instanceof String) || !(it ==~ /[A-Za-z_][A-Za-z0-9_.-]*/) }) throw new IllegalStateException('Native breadcrumbs contain unsupported page names')
    "/installedapp/configure/json/${id}" + (pages ? '/' + pages.collect { _nfEncode(it) }.join('/') : '')
}

private void _nfCache(String key, Map context) {
    // Retain only execution descriptors. Titles, messages, values and option labels stay in the current response.
    Map cached = [app_id: context.app_id, page: [name: context.page.name], envelope: context.envelope, expires: context.expires,
                  fields: [:], controls: [:], backup: context.backup]
    context.get('fields').each { name, field ->
        Map descriptor = field.findAll { k, v -> k in ['name', 'type', 'multiple', 'required', 'supported', 'device', 'disabled', 'present', 'capability', 'ignore', 'range', 'min', 'max', 'step', 'default_value'] }
        descriptor.option_keys = field.options == null ? null : field.options*.value
        cached.get('fields').put(name, descriptor)
    }
    context.controls.each { name, control ->
        cached.controls.put(name, control.findAll { k, v -> k in ['name', 'value', 'kind', 'stateAttribute', 'submit_on_change', 'params', 'disabled', 'page'] })
    }
    int size = groovy.json.JsonOutput.toJson(cached).getBytes('UTF-8').length
    if (size > 24576) throw new IllegalStateException('Native execution descriptors exceed the 24 KiB per-app context bound')
    cached.bytes = size
    synchronized (NATIVE_FORM_CONTEXTS) {
        NATIVE_FORM_CONTEXTS.entrySet().findAll { entry -> entry.value.expires < now() && !NATIVE_FORM_BUSY.containsKey(entry.key) }.each { entry -> NATIVE_FORM_CONTEXTS.remove(entry.key) }
        int total = NATIVE_FORM_CONTEXTS.findAll { k, v -> k != key }.values().sum { it.bytes } ?: 0
        if (total + size > 98304 || (!NATIVE_FORM_CONTEXTS.containsKey(key) && NATIVE_FORM_CONTEXTS.size() >= 8)) throw new IllegalStateException('Native form context capacity reached; inactive caller workflows expire after five minutes')
        NATIVE_FORM_CONTEXTS.put(key, cached)
    }
}

private List _nfDevices(Map field) {
    _nrDeviceChoices(field).collect { device ->
        [value: device.id.toString(), label: _nfText(device.label ?: device.name)]
    }
}

private String _nfValue(Map field, Object value) {
    if (!field.supported || field.disabled || field.name =~ /[\[\]\r\n]/) throw new IllegalArgumentException("Unsupported or disabled native field: ${field.name}")
    Map descriptor = [:] + field
    descriptor.option_keys = field.containsKey('option_keys') ? field.option_keys : (field.options == null ? null : field.options*.value)
    _nrValidateInput(descriptor, value, true)
    List values = value instanceof List ? value : [value]
    if (field.device) return values.collect { it.toString() }.join(',')
    if (field.multiple) return groovy.json.JsonOutput.toJson(values.collect { it.toString() })
    // The classic browser removes markup from visible scalar inputs and escapes any remaining '<'.
    if (!(field.type in ['bool', 'enum', 'mode'])) {
        String text = value.toString(), prior = null
        while (text != prior) { prior = text; text = text.replaceAll(/(?is)<!--.*?-->|<script\b[^>]*>.*?<\/script\s*>|<style\b[^>]*>.*?<\/style\s*>|<\/?[a-z](?:[^"'>]|"[^"]*"|'[^']*')*>/, '') }
        return text.replace('<', '&lt;')
    }
    value.toString()
}

private Map _nfPublic(Map context, List optionsFor) {
    List fields = context.get('fields').values().collect { field ->
        Map result = [:] + field
        result.remove('device'); result.remove('ignore'); result.remove('capability'); result.remove('present'); result.remove('default_value')
        if (field.type == 'password') result.redacted = true
        if (field.device && field.name in optionsFor) result.options = _nfDevices(field)
        if (field.device) result.options_available = true
        result
    }
    [page: context.page, fields: fields, controls: context.controls.values().collect { control ->
        control.findAll { key, value -> !(key in ['params', 'stateAttribute']) }
    }, messages: context.messages, unsupported: context.unsupported]
}

// The recovery point a form workflow takes before its first render: Rule Machine rules use their
// exact native export, the Rule Machine parent has no child state to restore, and every other
// classic app uses the generic configuration snapshot of the page being worked on (page is null for
// the main page), since rendering another page can reset a stateful wizard.
private Map _nfBackup(Long id, def nativeApp, String page) {
    boolean builtIn = nativeApp?.appType?.namespace == 'hubitat'
    if (builtIn && nativeApp.appType.name == 'Rule Machine') return [skipped: true, reason: 'Parent creation/navigation has no existing child state to restore; opening a child starts its own protected workflow']
    if (builtIn && nativeApp.appType.name in ['Rule-5.1', 'Button Rule-5.1']) return _ndEditBackup(id, 'pre-native-form')
    if (_effectiveBackupSetting('backupBeforeRuleEdits') == 'false') return [skipped: true, reason: 'Automatic pre-edit backups are disabled']
    _rmBackupBeforeEdit(id.toInteger(), 'pre-native-form', page)
}

private Map _nfDefaultsReport(List posted, List rejected) {
    (posted ? [defaults_posted: posted.unique()] : [:]) + (rejected ? [defaults_not_posted: rejected] : [:])
}

Map toolCallAppForm(Map args, String toolName = 'hub_set_rule') {
    if (args.keySet().any { !(it in ['app_id', 'steps', 'options_for', 'confirm', 'bestPracticeKey']) } || !args.app_id?.toString()?.isLong() || args.app_id.toString().toLong() < 1) throw new IllegalArgumentException('A positive app_id is required')
    if (!(args.steps instanceof List) || args.steps.size() < 1 || args.steps.size() > 16) throw new IllegalArgumentException('Provide 1 through 16 open/submit steps')
    List optionsFor = args.options_for ?: []
    if (!(optionsFor instanceof List) || optionsFor.size() > 32 || optionsFor.any { !(it instanceof String) }) throw new IllegalArgumentException('options_for must contain at most 32 field names')
    args.steps.each { step ->
        if (!(step instanceof Map) || !(step.kind in ['open', 'submit'])) throw new IllegalArgumentException('Unknown form step')
        List keys = step.kind == 'open' ? ['kind', 'page', 'from'] : ['kind', 'expect_page', 'fields', 'activate']
        if (step.keySet().any { !(it in keys) }) throw new IllegalArgumentException('Unknown form step property')
        if (step.kind == 'open' && step.page != null && (!(step.page instanceof String) || !(step.page ==~ /[A-Za-z_][A-Za-z0-9_.-]*/))) throw new IllegalArgumentException('A registered native page name is required')
        if (step.kind == 'open' && step.from != null && (step.page == null || !(step.from instanceof String) || !(step.from ==~ /[A-Za-z_][A-Za-z0-9_.-]*/))) throw new IllegalArgumentException('from names the parent page of the opened page and requires page')
        if (step.kind == 'submit' && (!(step.expect_page instanceof String) || (step.get('fields') != null && !(step.get('fields') instanceof Map)))) throw new IllegalArgumentException('Submit requires expect_page and an optional fields object')
        if (step.activate != null && !(step.activate instanceof String)) throw new IllegalArgumentException('activate requires a returned control_id string')
    }
    requireDestructiveConfirm(args.confirm)
    Long id = args.app_id.toString().toLong()
    _requireUnprotectedAppMutation(id, 'edit native form')
    def nativeApp = getAppByAppId(id)
    if (nativeApp?.id?.toString() != id.toString()) throw new IllegalArgumentException('app_id must identify an installed app')
    if (nativeApp.disabled == true) throw new IllegalArgumentException('Enable the app before editing its form')
    String key = _nfCallerKey()
    String appKey = _nfKey(id)
    if (NATIVE_FORM_BUSY.putIfAbsent(key, true) != null) throw new IllegalStateException('This caller already has an active form operation')
    if (NATIVE_FORM_BUSY.putIfAbsent(appKey, true) != null) {
        NATIVE_FORM_BUSY.remove(key)
        throw new IllegalStateException('This app already has an active form operation')
    }
    List completed = []
    List defaultsPosted = []
    List defaultsNotPosted = []
    boolean attempted = false
    Long createdId = null
    Map navigation = null
    Map context = NATIVE_FORM_CONTEXTS[key]
    Map backup = null
    try {
        if (context && context.expires < now()) { NATIVE_FORM_CONTEXTS.remove(key); context = null }
        if (context && context.app_id != id) context = null
        backup = args.steps[0].kind == 'open' ? null : context?.backup
        args.steps.eachWithIndex { step, index ->
            Map model
            if (step.kind == 'open') {
                // An explicit open starts this caller's next workflow, even if rendering fails.
                NATIVE_FORM_CONTEXTS.remove(key)
                context = null
                if (backup == null) backup = _nfBackup(id, nativeApp, step.page)
                attempted = true
                model = _nfJson(hubInternalGet("/installedapp/configure/json/${id}" + (step.page ? "/${_nfEncode(step.page)}" : '')))
            } else {
                if (!context) throw new IllegalArgumentException('Open the native form first; its context has expired or is unavailable')
                if (step.expect_page != context.page.name) throw new IllegalArgumentException('expect_page does not match the last returned native page')
                Map body = [:] + context.envelope
                Map supplied = step.get('fields') ?: [:]
                supplied.each { name, value ->
                    Map field = context.get('fields').get(name)
                    if (!field) throw new IllegalArgumentException("Field is not present on the current native page: ${name}")
                    body["settings[${name}]".toString()] = _nfValue(field, value)
                    body["${name}.type".toString()] = field.type
                    body["${name}.multiple".toString()] = field.multiple.toString()
                }
                // The browser posts every input on the page, so an unset input saves its default.
                List stepDefaults = []
                List stepRejected = []
                context.get('fields').each { name, field ->
                    if (supplied.containsKey(name) || field.present || !field.containsKey('default_value') || field.disabled || !field.supported) return
                    String encoded
                    try { encoded = _nfValue(field, field.default_value) }
                    catch (IllegalArgumentException rejected) {
                        mcpLog('debug', 'rm-native', "${toolName} form: page default of ${name} on app ${id} not posted: ${rejected.message}")
                        stepRejected << [name: name, reason: rejected.message]
                        return
                    }
                    stepDefaults << name
                    body["settings[${name}]".toString()] = encoded
                    body["${name}.type".toString()] = field.type
                    body["${name}.multiple".toString()] = field.multiple.toString()
                }
                Map control = null
                String reloadPath = null
                if (step.activate) {
                    control = context.controls[step.activate]
                    if (!control) throw new IllegalArgumentException('Choose one exact returned native control_id')
                    if (control.disabled) throw new IllegalArgumentException('The native control is disabled')
                    if (control.kind == 'local_app' && step.get('fields')) throw new IllegalArgumentException('Submit fields separately before opening another native app form')
                    if (control.kind == 'create_child' && step.get('fields')) throw new IllegalArgumentException('Submit parent fields separately before creating a child')
                    if (control.kind == 'button' && !control.submit_on_change && step.get('fields')) throw new IllegalArgumentException('Submit fields separately before a button that reloads the native page')
                    if (control.kind == 'button' && !control.submit_on_change) reloadPath = _nfReloadPath(id, context)
                    if (control.kind in ['href', 'navigation'] && control.value != 'Cancel') {
                        List missing = context.get('fields').values().findAll { field -> field.required && !field.disabled &&
                            !(body.containsKey("settings[${field.name}]".toString()) ? !(body["settings[${field.name}]".toString()] in ['', '[]']) : field.present) }*.name
                        if (missing) throw new IllegalArgumentException("Complete required native fields before navigating: ${missing.join(', ')}")
                    }
                }
                if (backup == null) backup = _nfBackup(id, nativeApp, context.page.name ?: null)
                attempted = true
                if (control?.kind == 'local_app') {
                    navigation = _nfVerifyNavigation(id, [app_id: control.value.toString().toLong(), page: control.stateAttribute])
                    model = [location: 'local_app']
                } else if (control?.kind == 'create_child') {
                    // The native HTTP client encodes the path; pre-encoding names escapes percent signs twice.
                    String childName = control.value.toString()
                    createdId = _nrCreatedAppId(hubInternalGetRaw("/installedapp/createchild/hubitat/${childName}/parent/${id}"))
                    if (createdId == null) throw new IllegalStateException('Native child creation did not return an app ID; inspect the parent before retrying')
                    def child = getAppByAppId(createdId)
                    if (child?.parentAppId?.toString() != id.toString() || child?.appType?.namespace != 'hubitat' || child?.appType?.name != control.value) throw new IllegalStateException('Created app did not match the native parent and child type')
                    model = [location: 'child_created']
                } else if (control?.kind == 'button') {
                    Map button = [id: id, name: control.name]
                    button["settings[${control.name}]".toString()] = 'clicked'
                    button["${control.name}.type".toString()] = 'button'
                    if (control.stateAttribute) button.stateAttribute = control.stateAttribute
                    _nfPost('/installedapp/btn', button)
                    if (!control.submit_on_change) model = _nfJson(hubInternalGet(reloadPath))
                } else if (control) {
                    body.put(control.name, control.value ?: '')
                    if (control.params != null) body["params_for${control.name}".toString()] = _nfEncode(groovy.json.JsonOutput.toJson(control.params))
                }
                if (model == null) {
                    model = _nfPost('/installedapp/update/json', body)
                    defaultsPosted.addAll(stepDefaults)
                    defaultsNotPosted.addAll(stepRejected)
                }
            }
            if (!navigation) navigation = _nfNavigation(id, model)
            if (model.location || navigation) {
                NATIVE_FORM_CONTEXTS.remove(key); context = null
                completed << [kind: step.kind, navigation_ended: true]
                if (index + 1 < args.steps.size()) throw new IllegalStateException('Native navigation ended the form; remaining steps were not executed')
            } else {
                context = _nfContext(id, model, step.kind == 'open' ? step.from : null)
                context.backup = backup
                _nfCache(key, context)
                completed << [kind: step.kind, page: context.page.name]
            }
        }
        [success: true, app_id: id, completed: completed, backup: backup] + _nfDefaultsReport(defaultsPosted, defaultsNotPosted) + (navigation ? [navigated_app_id: navigation.app_id, navigated_page: navigation.page] : [:]) + (createdId == null ? [:] : [created_app_id: createdId]) + (context ? _nfPublic(context, optionsFor) : [closed: true])
    } catch (Exception failure) {
        mcpLogError('rm-native', "${toolName} form failed for app ${id}: ${failure.message}".toString(), failure)
        // A lost response can follow a successful write; stale controls must never be replayed automatically.
        if (attempted) NATIVE_FORM_CONTEXTS.remove(key)
        [success: false, app_id: id, partial: attempted, completed: completed, error: failure.message?.toString(),
         reopen_required: attempted, remaining_steps: args.steps.size() - completed.size()] + _nfDefaultsReport(defaultsPosted, defaultsNotPosted) + (backup == null ? [:] : [backup: backup]) + (navigation ? [navigated_app_id: navigation.app_id, navigated_page: navigation.page, closed: true] : [:]) + (createdId == null ? [:] : [created_app_id: createdId])
    } finally { NATIVE_FORM_BUSY.remove(appKey); NATIVE_FORM_BUSY.remove(key) }
}

