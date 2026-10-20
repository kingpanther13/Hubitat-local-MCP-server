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
    while (matches.find()) attrs[matches.group(1).toLowerCase()] = _nfEntities(matches.group(2) != null ? matches.group(2) : matches.group(3))
    attrs
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
                               stateAttribute: attrs['data-stateattribute'], submit_on_change: (attrs['class'] ?: '').tokenize().contains('submitOnChange')]
                if (attrs['data-confirm']) control.confirmation = attrs['data-confirm']
                _nfControl(controls, control)
                found = true
            }
        }
        if (!found) unsupported << "Unrecognized HTML button: ${button.name}"
    }
    if (html =~ /(?is)<script\b|\bonclick\s*=\s*["'](?!buttonClick\(this\))/) unsupported << 'Custom browser script'
    if (html =~ /(?is)<a\b[^>]*href\s*=\s*["'](?!#["'])/) unsupported << 'HTML navigation link'
}

private void _nfControl(Map controls, Map control) {
    String key = [control.name, control.value ?: '', control.stateAttribute ?: ''].collect { _nfEncode(it) }.join('|')
    control.control_id = key
    if (controls.containsKey(key) && controls[key] != control) throw new IllegalStateException('Native form contains ambiguous controls')
    controls[key] = control
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
            field.present = exists && current != null && current != '' && (!(current instanceof Collection) || !current.empty)
            field.options = _nfOptions(type == 'mode' ? model.modeMap : node.options)
            if (device) {
                field.capability = type
                field.ignore = (node.ignoreDevices instanceof List ? node.ignoreDevices : (node.ignoreDevices?.toString()?.tokenize(',') ?: [])).collect { it.toString() }
            }
            if (fields.containsKey(name) && fields[name] != field) throw new IllegalStateException('Native form contains ambiguous fields')
            fields[name] = field
            if (!supported) unsupported << "Input type: ${type}"
        }
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

private Map _nfContext(Long id, Map model) {
    if (model.app?.id?.toString() != id.toString() || !(model.configPage instanceof Map)) throw new IllegalStateException('Native response does not identify the requested app and classic form')
    Map page = model.configPage
    Map fields = [:], controls = [:]
    List messages = [], unsupported = []
    _nfWalk(page, fields, controls, messages, unsupported, model)
    if (page.install || model.doneButton) _nfControl(controls, [name: '_action_update', value: 'Done', kind: 'navigation', title: model.cancelButton ? 'Save' : 'Done'])
    else if (page.nextPage && !page.donePage) _nfControl(controls, [name: '_action_next', value: page.nextPageLabel ?: 'Next', kind: 'navigation', title: page.nextPageLabel ?: 'Next'])
    else if (page.nextPage && page.donePage) unsupported << 'Native _action_done browser navigation'
    else if (model.pageBreadcrumbs && model.pageBreadcrumbs != '%5B%5D') _nfControl(controls, [name: '_action_previous', value: 'Done', kind: 'navigation', title: 'Done (previous page)'])
    if (model.cancelButton) _nfControl(controls, [name: '_action_update', value: 'Cancel', kind: 'navigation', title: 'Cancel'])
    if (model.removeButton) unsupported << 'Remove app: use the dedicated native-app deletion tool'
    if (model.defaultFields) unsupported << 'Default app label and mode controls'
    Map envelope = [id: id.toString(), currentPage: page.name ?: '', pageBreadcrumbs: model.pageBreadcrumbs ?: '%5B%5D']
    ['returnPath', 'referrer', 'url', 'formAction'].each { key -> if (model.containsKey(key)) envelope[key] = model[key] }
    ['nextPage', 'donePage'].each { key -> if (page[key]) envelope[key] = page[key] }
    if (page.returnPath != null) envelope.returnPath = page.returnPath
    if (page.returnPath == 'list') envelope.referrer = '/installedapp/list?section='
    if (model.app.containsKey('version')) envelope.version = model.app.version
    if (model.cancelButton != null) envelope._cancellable = model.cancelButton
    if (model.paramsForPage instanceof Map) envelope.paramsForPage = _nfEncode(groovy.json.JsonOutput.toJson(model.paramsForPage))
    Map context = [page: [name: page.name ?: '', title: _nfText(page.title)], fields: fields, controls: controls, messages: messages.unique(),
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
    Map cached = [page: [name: context.page.name], envelope: context.envelope, expires: context.expires,
                  fields: [:], controls: [:]]
    context.fields.each { name, field ->
        Map descriptor = field.findAll { k, v -> k in ['name', 'type', 'multiple', 'required', 'supported', 'device', 'disabled', 'present', 'capability', 'ignore', 'range', 'min', 'max', 'step'] }
        descriptor.option_keys = field.options == null ? null : field.options*.value
        cached.fields[name] = descriptor
    }
    context.controls.each { name, control ->
        cached.controls[name] = control.findAll { k, v -> k in ['name', 'value', 'kind', 'stateAttribute', 'submit_on_change', 'params', 'disabled'] }
    }
    int size = groovy.json.JsonOutput.toJson(cached).getBytes('UTF-8').length
    if (size > 24576) throw new IllegalStateException('Native execution descriptors exceed the 24 KiB per-app context bound')
    cached.bytes = size
    synchronized (NATIVE_FORM_CONTEXTS) {
        NATIVE_FORM_CONTEXTS.entrySet().findAll { entry -> entry.value.expires < now() }.each { entry -> NATIVE_FORM_CONTEXTS.remove(entry.key) }
        int total = NATIVE_FORM_CONTEXTS.findAll { k, v -> k != key }.values().sum { it.bytes } ?: 0
        if (total + size > 98304 || (!NATIVE_FORM_CONTEXTS.containsKey(key) && NATIVE_FORM_CONTEXTS.size() >= 8)) throw new IllegalStateException('Native form context capacity reached; inactive contexts expire after five minutes')
        NATIVE_FORM_CONTEXTS[key] = cached
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
    // The classic browser removes markup from visible scalar inputs.
    if (!(field.type in ['bool', 'enum', 'mode'])) {
        String text = value.toString(), prior = null
        while (text != prior) { prior = text; text = text.replaceAll(/(?is)<!--.*?-->|<script\b[^>]*>.*?<\/script\s*>|<style\b[^>]*>.*?<\/style\s*>|<\/?[a-z](?:[^"'>]|"[^"]*"|'[^']*')*>/, '') }
        return text.replace('<', '&lt;')
    }
    value.toString()
}

private Map _nfPublic(Map context, List optionsFor) {
    List fields = context.fields.values().collect { field ->
        Map result = [:] + field
        result.remove('device'); result.remove('ignore'); result.remove('capability'); result.remove('present')
        if (field.type == 'password') result.redacted = true
        if (field.device && field.name in optionsFor) result.options = _nfDevices(field)
        if (field.device) result.options_available = true
        result
    }
    [page: context.page, fields: fields, controls: context.controls.values().collect { control ->
        control.findAll { key, value -> !(key in ['params', 'stateAttribute']) }
    }, messages: context.messages, unsupported: context.unsupported]
}

Map toolCallAppForm(Map args) {
    if (args.keySet().any { !(it in ['app_id', 'steps', 'options_for', 'confirm', 'bestPracticeKey']) } || !args.app_id?.toString()?.isLong() || args.app_id.toString().toLong() < 1) throw new IllegalArgumentException('A positive app_id is required')
    if (!(args.steps instanceof List) || args.steps.size() < 1 || args.steps.size() > 16) throw new IllegalArgumentException('Provide 1 through 16 open/submit steps')
    List optionsFor = args.options_for ?: []
    if (!(optionsFor instanceof List) || optionsFor.size() > 32 || optionsFor.any { !(it instanceof String) }) throw new IllegalArgumentException('options_for must contain at most 32 field names')
    args.steps.each { step ->
        if (!(step instanceof Map) || !(step.kind in ['open', 'submit'])) throw new IllegalArgumentException('Unknown form step')
        List keys = step.kind == 'open' ? ['kind', 'page'] : ['kind', 'expect_page', 'fields', 'activate']
        if (step.keySet().any { !(it in keys) }) throw new IllegalArgumentException('Unknown form step property')
        if (step.kind == 'open' && step.page != null && (!(step.page instanceof String) || !(step.page ==~ /[A-Za-z_][A-Za-z0-9_.-]*/))) throw new IllegalArgumentException('A registered native page name is required')
        if (step.kind == 'submit' && (!(step.expect_page instanceof String) || (step.fields != null && !(step.fields instanceof Map)))) throw new IllegalArgumentException('Submit requires expect_page and an optional fields object')
        if (step.activate != null && !(step.activate instanceof String)) throw new IllegalArgumentException('activate requires a returned control_id string')
    }
    requireDestructiveConfirm(args.confirm)
    Long id = args.app_id.toString().toLong()
    def nativeApp = getAppByAppId(id)
    if (nativeApp?.appType?.namespace != 'hubitat') throw new IllegalArgumentException('app_id must identify a built-in native app')
    if (nativeApp.disabled == true) throw new IllegalArgumentException('Enable the native app before editing its form')
    String key = _nfKey(id)
    if (NATIVE_FORM_BUSY.putIfAbsent(key, true) != null) throw new IllegalStateException('This app already has an active form operation')
    List completed = []
    boolean attempted = false
    Map context = NATIVE_FORM_CONTEXTS[key]
    try {
        if (context && context.expires < now()) { NATIVE_FORM_CONTEXTS.remove(key); context = null }
        args.steps.eachWithIndex { step, index ->
            Map model
            if (step.kind == 'open') {
                attempted = true
                model = _nfJson(hubInternalGet("/installedapp/configure/json/${id}" + (step.page ? "/${_nfEncode(step.page)}" : '')))
            } else {
                if (!context) throw new IllegalArgumentException('Open the native form first; its context has expired or is unavailable')
                if (step.expect_page != context.page.name) throw new IllegalArgumentException('expect_page does not match the last returned native page')
                Map body = [:] + context.envelope
                (step.fields ?: [:]).each { name, value ->
                    Map field = context.fields[name]
                    if (!field) throw new IllegalArgumentException("Field is not present on the current native page: ${name}")
                    body["settings[${name}]".toString()] = _nfValue(field, value)
                    body["${name}.type".toString()] = field.type
                    body["${name}.multiple".toString()] = field.multiple.toString()
                }
                Map control = null
                String reloadPath = null
                if (step.activate) {
                    control = context.controls[step.activate]
                    if (!control) throw new IllegalArgumentException('Choose one exact returned native control_id')
                    if (control.disabled) throw new IllegalArgumentException('The native control is disabled')
                    if (control.kind == 'button' && !control.submit_on_change && step.fields) throw new IllegalArgumentException('Submit fields separately before a button that reloads the native page')
                    if (control.kind == 'button' && !control.submit_on_change) reloadPath = _nfReloadPath(id, context)
                    if (control.kind in ['href', 'navigation'] && control.value != 'Cancel') {
                        List missing = context.fields.values().findAll { field -> field.required && !field.disabled &&
                            !((step.fields ?: [:]).containsKey(field.name) ? body["settings[${field.name}]".toString()] != '' && body["settings[${field.name}]".toString()] != '[]' : field.present) }*.name
                        if (missing) throw new IllegalArgumentException("Complete required native fields before navigating: ${missing.join(', ')}")
                    }
                }
                attempted = true
                if (control?.kind == 'button') {
                    Map button = [id: id, name: control.name]
                    button["settings[${control.name}]".toString()] = 'clicked'
                    button["${control.name}.type".toString()] = 'button'
                    if (control.stateAttribute) button.stateAttribute = control.stateAttribute
                    _nfPost('/installedapp/btn', button)
                    if (!control.submit_on_change) model = _nfJson(hubInternalGet(reloadPath))
                } else if (control) {
                    body[control.name] = control.value ?: ''
                    if (control.params != null) body["params_for${control.name}".toString()] = _nfEncode(groovy.json.JsonOutput.toJson(control.params))
                }
                if (model == null) model = _nfPost('/installedapp/update/json', body)
            }
            if (model.location) {
                NATIVE_FORM_CONTEXTS.remove(key); context = null
                completed << [kind: step.kind, navigation_ended: true]
                if (index + 1 < args.steps.size()) throw new IllegalStateException('Native navigation ended the form; remaining steps were not executed')
            } else {
                context = _nfContext(id, model)
                _nfCache(key, context)
                completed << [kind: step.kind, page: context.page.name]
            }
        }
        [success: true, app_id: id, completed: completed] + (context ? _nfPublic(context, optionsFor) : [closed: true])
    } catch (Exception failure) {
        // A lost response can follow a successful write; stale controls must never be replayed automatically.
        if (attempted) NATIVE_FORM_CONTEXTS.remove(key)
        [success: false, app_id: id, partial: attempted, completed: completed, error: failure.message?.toString(),
         reopen_required: attempted, remaining_steps: args.steps.size() - completed.size()]
    } finally { NATIVE_FORM_BUSY.remove(key) }
}

def _getAllToolDefinitions_partNativeForms() {
    def step = [type: 'object', additionalProperties: false, properties: [
        kind: [type: 'string', enum: ['open', 'submit']], page: [type: 'string'], expect_page: [type: 'string'],
        fields: [type: 'object'], activate: [type: 'string', description: 'Exact control_id returned by the current form.']
    ], required: ['kind']]
    return [[
      name: 'hub_call_app_form',
      description: 'Open or submit built-in native app forms using returned field/control names. Rendering executes app code and can change state. Requires confirm=true and a recent backup. Ordered batches may partially apply; never automatically retry buttons. Browser extensions are reported unsupported.',
      inputSchema: [type: 'object', additionalProperties: false, properties: [app_id: [type: 'integer', minimum: 1],
        steps: [type: 'array', minItems: 1, maxItems: 16, items: step], options_for: [type: 'array', maxItems: 32, items: [type: 'string']], confirm: [type: 'boolean']],
        required: ['app_id', 'steps', 'confirm']]]]
}

def _toolDisplayMeta_partNativeForms() {
    [hub_call_app_form: [title: 'Call Native App Form', summary: 'Inspect and submit native classic forms, including page links and buttons.']]
}

String _nfGuide() {
    return '''## Native App Forms

Use hub_call_app_form(app_id,steps,confirm:true) to operate an enabled built-in native app's classic editor. Even open renders execute app code and can change state or cause side effects. Start with steps:[{kind:"open"}]; an optional page names a registered native page. Follow returned controls for page parameters and breadcrumbs. App removal uses the dedicated deletion tool.

Submit with {kind:"submit",expect_page:"last returned page",fields:{"native field name":value},activate:"returned control_id"}. Omit activate to save the supplied fields and refresh the current page. Control IDs distinguish native buttons sharing a name, such as editing and deleting the same variable. Native enum keys, device IDs, booleans and single/multiple cardinality are checked before the request. Only supplied settings are posted; native page code can still change its own settings. Defaults are shown but are not automatically submitted; supply a required default explicitly before navigating. Use options_for:["device field name"] to expand device choices on the final returned page.

Batches contain 1 through 16 ordered steps. Each step uses the form returned by the preceding step, so dependent fields require another submit after they appear. A button handler runs before that step's fields are saved, as in the native browser; submit fields in a preceding step when the handler needs them. A button without submitOnChange reloads and requires a separate field submission.

The execution context expires after five minutes and is lost on app reload. It retains no field values, messages or option labels; limits are 24 KiB of serialized descriptors per app, 96 KiB total, and eight apps. expect_page guards the latest tool context, not concurrent edits in another browser. Avoid concurrent editing. Errors can leave completed operations or an uncertain final operation; inspect the app and reopen instead of replaying buttons. Terminal navigation closes the context without following redirects. App-authored JavaScript, custom HTML inputs, uploads, external/child links and unsupported browser controls are reported in unsupported; they are never silently executed. Success means the native form operation returned, not that the app's intended runtime behavior was independently verified.'''
}
