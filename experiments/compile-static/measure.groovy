// Offline experiment runner only. Never executes a Hubitat app's top-level DSL.
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.MethodVisitor
import groovyjarjarasm.asm.Opcodes

assert args.length == 3: 'usage: measure.groovy <repo-root> <discover|verify> <output-dir>'
File root = new File(args[0]).canonicalFile
String mode = args[1]
assert mode in ['discover', 'verify']
File out = new File(args[2]).canonicalFile
out.mkdirs()
Class resolver = new GroovyClassLoader().parseClass(new File(root, 'src/test/groovy/support/IncludeResolver.groovy'))
File app = new File(root, 'hubitat-mcp-server.groovy')
String current = resolver.resolveFile(app)
String annotation = '@groovy.transform.CompileStatic'
String baseline = current.replaceAll(/(?m)^\s*@groovy\.transform\.CompileStatic\s*\r?\n/, '')
// Stock 2.4 accepts these, but the existing typed harness fails static checking under 2.5/3.0.
Map compilerExclusions = [
    _logsJsonTable: 'Groovy 2.5/3.0 loses List narrowing after the table.any closure; return table is inferred as Object',
    _radioRefused: 'Groovy 2.5/3.0 does not narrow resp to Map on the right side of a negated instanceof OR'
]

Map helpers = [
    overrideOptionLabel: [['hub_test', [title: 'Example', summary: 'Example summary.'], true], ['hub_test', [:], false], ['hub_test', null, true]],
    _authorityHost: [[null], [''], ['user@Example.COM:8080/path'], ['[::1]:8080/path'], ['[missing'], [' host '], ['host:80']],
    _decodeHeaderValue: [[null], ['plain'], ['=?base64?aGVsbG8=?='], ['=?base64?@@?='], ['=?base64?A?='], ['=?BASE64?aGVsbG8=?=']],
    _mcpClientIsWrapper: [[null, null], ['MCP', null], ['mcp', '0.1.0'], ['mcp', '1.0'], ['mcp-remote', '2'], ['mcp-remote-control', null]],
    _mcpClientString: [[null], [''], ['  hello\nworld\t`test`  '], ['a' * 140], [42]],
    _flooredBudgetMs: [[-1L], [0L], [1L], [5999L], [6000L], [10000L], [Long.MAX_VALUE]],
    _mrtrShiftIndex: [[null, 'item[', 1], ['item[2] item[19]', 'item[', 3], ['item[x] item[4', 'item[', 2], ['item[2]', 'item[', 0], ['item[2]', 'item[', -3]],
    _stripLibraryMarkers: [[null], ['unchanged'], ['a // library marker mcp.Example, line 42 b'], ['// library marker mcp.Example, line 1\nhello']],
    _redactSecretsInPath: [[null], ['/path'], ['/path?psk=secret&x=1&password=pass'], ['/path?PSW=secret'], ['/password=']]
]

def parse = { String source ->
    def unit = new CompilationUnit(new CompilerConfiguration())
    unit.addSource('hubitat-mcp-server.groovy', source)
    unit.compile(Phases.CONVERSION)
    unit.AST.classes.find { it.script }.methods.findAll { it.lineNumber > 0 && !it.synthetic }
}
def compile = { String source ->
    def unit = new CompilationUnit(new CompilerConfiguration())
    unit.addSource('hubitat-mcp-server.groovy', source)
    unit.compile(Phases.CLASS_GENERATION)
    unit.classes
}
def metrics = { classes ->
    Map result = [classes: classes.size(), totalBytes: 0, parentConstantPool: 0, dynamicCallSiteSlots: 0, dynamicDispatchInstructions: 0]
    classes.each { generated ->
        byte[] bytes = generated.bytes
        result.totalBytes += bytes.length
        if (!generated.name.contains('$')) result.parentConstantPool = ((bytes[8] & 255) << 8) | (bytes[9] & 255)
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    void visitLdcInsn(Object value) {
                        if (name.startsWith('$createCallSiteArray_') && value instanceof String) result.dynamicCallSiteSlots++
                    }
                    void visitMethodInsn(int opcode, String owner, String invoked, String invokedDescriptor, boolean itf) {
                        if (owner.startsWith('org/codehaus/groovy/runtime/callsite/')) result.dynamicDispatchInstructions++
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    }
    result
}
def shape
shape = { value ->
    if (value == null) return null
    if (value instanceof Map) return [type: value.getClass().name, entries: value.collect { k, v -> [shape(k), shape(v)] }]
    if (value instanceof Collection) return [type: value.getClass().name, values: value.collect { shape(it) }]
    if (value instanceof GString) return [type: value.getClass().name.replaceAll(/Candidate.*?\$_/, 'Candidate\$_'), text: value.toString()]
    return [type: value.getClass().name, value: value]
}
def invoke = { instance, method, List params ->
    try {
        def target = instance.getClass().declaredMethods.find { it.name == method && it.parameterTypes.length == params.size() }
        assert target != null
        target.accessible = true
        return [value: shape(target.invoke(instance, params as Object[]))]
    } catch (java.lang.reflect.InvocationTargetException error) {
        return [error: error.cause.getClass().name, message: error.cause.message]
    }
}

List lines = baseline.readLines()
List candidates = parse(baseline).sort { it.lineNumber }
Map overloads = candidates.groupBy { it.name }
List selected = []
List results = []
candidates.each { method ->
    String body = lines[(method.lineNumber - 1)..(method.lastLineNumber - 1)].join('\n')
    String dynamic = "class Candidate {\n${body}\n}"
    String statically = "class Candidate {\n${annotation}\n${body}\n}"
    Map record = [method: method.name, line: method.lineNumber]
    if (compilerExclusions.containsKey(method.name)) {
        record.status = 'harness-compiler-excluded'
        record.reason = compilerExclusions[method.name]
        results << record
        return
    }
    boolean catalog = method.parameters.length == 0 && method.name ==~ /_(getAllToolDefinitions|readOnlyToolNames|idempotentWriteToolNames|openWorldToolNames|developerModeOnlyToolNames|toolDisplayMeta)_part\w+/
    // Standalone compilation rejects undeclared platform bindings and cross-method calls.
    // Explicitly exclude dynamic hooks and nondeterministic runtime APIs even when typed.
    if (overloads[method.name].size() > 1 || body =~ /\b(metaClass|getMetaClass|getClass|respondsTo|invokeMethod|hasProperty|propertyMissing|methodMissing|System|Date|Calendar|Random|UUID)\b|Math\.random/) {
        record.status = 'runtime-dependency-excluded'
        results << record
        return
    }
    try {
        Map after = metrics(compile(statically))
        Map before = metrics(compile(dynamic))
        List vectors = helpers[method.name] ?: (catalog ? [[]] : [])
        if (vectors) {
            GroovyClassLoader a = new GroovyClassLoader()
            GroovyClassLoader b = new GroovyClassLoader()
            try {
            def original = a.parseClass(dynamic).newInstance()
            def modified = b.parseClass(statically).newInstance()
            vectors.each { vector ->
                assert invoke(original, method.name, vector) == invoke(modified, method.name, vector): "behavior differs for ${method.name} ${vector}"
            }
            if (catalog) {
                // Catalogs must remain independently mutable on each invocation.
                def target = original.getClass().declaredMethods.find { it.name == method.name }
                target.accessible = true
                def oldValue = target.invoke(original)
                target = modified.getClass().declaredMethods.find { it.name == method.name }
                target.accessible = true
                def newValue = target.invoke(modified)
                if (oldValue instanceof Map || oldValue instanceof Collection) {
                    oldValue.clear(); newValue.clear()
                    assert invoke(original, method.name, []) == invoke(modified, method.name, [])
                    assert !target.invoke(modified).is(newValue)
                }
            }
            } finally { a.close(); b.close() }
        }
        record.before = before; record.after = after; record.parityCases = vectors.size()
        record.behaviorValidation = vectors ? 'standalone-parity' : 'requires-existing-full-suite'
        // Keep only methods with actual dynamic-dispatch reductions.
        if (after.dynamicDispatchInstructions < before.dynamicDispatchInstructions || after.dynamicCallSiteSlots < before.dynamicCallSiteSlots) {
            selected << method
            record.status = 'selected'
        } else record.status = 'no-dynamic-dispatch-reduction'
    } catch (Throwable error) {
        if (error instanceof AssertionError) throw error
        record.status = 'static-compile-rejected'
        record.error = error.message?.take(4000)
    }
    results << record
    println "${record.status}: ${method.name}"
}
new File(out, 'candidates.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(results)) + '\n'
List selectedNames = selected*.name
new File(out, 'selected.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(selectedNames)) + '\n'
String modified = baseline
selected.sort { -it.lineNumber }.each { method ->
    int offset = 0
    for (int i = 0; i < method.lineNumber - 1; i++) offset = modified.indexOf('\n', offset) + 1
    modified = modified.substring(0, offset) + annotation + '\n' + modified.substring(offset)
}
new File(out, 'candidate-inlined.groovy').text = modified
Map before = metrics(compile(baseline))
Map after = metrics(compile(modified))
Map report = [mode: mode, groovy: GroovySystem.version, declaredMethods: candidates.size(), statusCounts: results.countBy { it.status }, selected: selectedNames, before: before, after: after, parityCases: results.findAll { it.status == 'selected' }.sum { it.parityCases } ?: 0]
if (mode == 'verify') {
    List actual = parse(current).findAll { it.annotations.any { it.classNode.name == 'groovy.transform.CompileStatic' } }*.name
    assert actual.toSet() == selectedNames.toSet(): "annotations differ: actual=${actual}, selected=${selectedNames}"
    assert metrics(compile(current)) == after: 'production bytes differ from measured candidate metrics'
}
assert selectedNames: 'No conservative candidate reduces dynamic dispatch'
assert after.dynamicDispatchInstructions < before.dynamicDispatchInstructions
assert after.parentConstantPool < 64000: "candidate parent exceeds constant-pool budget: ${after.parentConstantPool}"
new File(out, 'measurement.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n'
println JsonOutput.prettyPrint(JsonOutput.toJson(report))
