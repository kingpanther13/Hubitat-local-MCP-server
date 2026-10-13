// CI-only, full-app candidate discovery. It never runs the app's top-level DSL.
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.classgen.VariableScopeVisitor
import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.MethodVisitor
import groovyjarjarasm.asm.Opcodes

assert args.length == 4: 'usage: connected.groovy <root> <output> <script-base-class> <classpath-flavor>'
File root = new File(args[0]).canonicalFile
File output = new File(args[1], args[3])
output.mkdirs()
Class resolver = new GroovyClassLoader().parseClass(new File(root, 'src/test/groovy/support/IncludeResolver.groovy'))
String source = resolver.resolveFile(new File(root, 'hubitat-mcp-server.groovy'))
List sourceLines = source.readLines()
String annotation = '@groovy.transform.CompileStatic'
def unitFor = { String text ->
    def config = new CompilerConfiguration()
    config.scriptBaseClass = args[2]
    def unit = new CompilationUnit(config)
    unit.addSource('hubitat-mcp-server.groovy', text)
    unit
}
def parsed = unitFor(source)
parsed.compile(Phases.CONVERSION)
def sourceUnit = parsed.iterator().next()
def scriptClass = parsed.AST.classes.find { it.script }
new VariableScopeVisitor(sourceUnit).visitClass(scriptClass)
List methods = scriptClass.methods.findAll { it.lineNumber > 0 && !it.synthetic }
Map byName = methods.groupBy { it.name }
Set retained = methods.findAll { it.annotations.any { a -> a.classNode.name == 'groovy.transform.CompileStatic' } }*.name as Set
Map records = [:]
Set classNames = [
    'java', 'groovy', 'org', 'Object', 'Math', 'String', 'CharSequence', 'Number', 'Byte', 'Short',
    'Integer', 'Long', 'Double', 'Float', 'BigDecimal', 'BigInteger', 'Boolean', 'Character',
    'StringBuilder', 'StringBuffer', 'Closure', 'Map', 'List', 'Set', 'Collection', 'Iterable',
    'Iterator', 'SortedMap', 'NavigableMap', 'TreeMap', 'HashMap', 'LinkedHashMap',
    'SortedSet', 'TreeSet', 'HashSet', 'LinkedHashSet', 'ArrayList', 'LinkedList',
    'Queue', 'Deque', 'Enumeration', 'Collections', 'Arrays', 'Objects', 'Locale',
    'Pattern', 'Matcher', 'URLEncoder', 'URLDecoder', 'JsonOutput', 'JsonSlurper',
    'Exception', 'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
    'UnsupportedOperationException', 'NullPointerException', 'ArithmeticException'
] as Set
methods.each { method ->
    if (retained.contains(method.name)) return
    Set dependencies = [] as Set
    Set locals = method.parameters*.name as Set
    List reasons = []
    method.code.visit(new ClassCodeVisitorSupport() {
        protected org.codehaus.groovy.control.SourceUnit getSourceUnit() { sourceUnit }
        void visitDeclarationExpression(DeclarationExpression expression) {
            if (!expression.multipleAssignmentDeclaration) locals.add(expression.variableExpression.name)
            super.visitDeclarationExpression(expression)
        }
    })
    method.code.visit(new ClassCodeVisitorSupport() {
        protected org.codehaus.groovy.control.SourceUnit getSourceUnit() { sourceUnit }
        void visitVariableExpression(VariableExpression expression) {
            if (expression.accessedVariable instanceof DynamicVariable && !classNames.contains(expression.name)) {
                reasons << "dynamic binding: ${expression.name}"
            }
            super.visitVariableExpression(expression)
        }
        void visitPropertyExpression(PropertyExpression expression) {
            if (expression.objectExpression instanceof VariableExpression && expression.objectExpression.thisExpression) {
                reasons << "script property: ${expression.propertyAsString}"
            }
            super.visitPropertyExpression(expression)
        }
        void visitSynchronizedStatement(SynchronizedStatement statement) {
            reasons << 'concurrency boundary'
            super.visitSynchronizedStatement(statement)
        }
        void visitMethodCallExpression(MethodCallExpression expression) {
            if (expression.implicitThis || (expression.objectExpression instanceof VariableExpression && expression.objectExpression.thisExpression)) {
                String name = expression.methodAsString
                if (byName.containsKey(name)) dependencies.add(name)
                else if (!locals.contains(name)) reasons << "dynamic dispatch: ${name}"
            }
            super.visitMethodCallExpression(expression)
        }
    })
    String body = sourceLines[(method.lineNumber - 1)..(method.lastLineNumber - 1)].join('\n')
    if (body =~ /\b(metaClass|getMetaClass|getClass|respondsTo|invokeMethod|hasProperty|propertyMissing|methodMissing|System|Date|Calendar|Random|UUID)\b|Math\.random/) reasons << 'runtime introspection or nondeterministic API'
    if (method.name in ['_logsJsonTable', '_radioRefused']) reasons << 'confirmed Groovy 2.5/3.0 narrowing failure'
    if (byName[method.name].size() != 1) reasons << 'overloaded method'
    records[method.name] = [method: method.name, line: method.lineNumber, dependencies: dependencies.toList(), reasons: reasons.unique()]
}
Set eligible = records.findAll { name, record -> !record.reasons }.keySet() as Set
boolean changed = true
while (changed) {
    Set remove = eligible.findAll { name -> records[name].dependencies.any { !eligible.contains(it) && !retained.contains(it) } } as Set
    remove.each { name -> records[name].reasons << 'depends on a dynamic/platform helper' }
    changed = !remove.isEmpty()
    eligible.removeAll(remove)
}
Set selected = new LinkedHashSet(eligible)
List failures = []
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
def annotate = { Set names ->
    String text = source
    methods.findAll { names.contains(it.name) }.sort { -it.lineNumber }.each { method ->
        int offset = 0
        for (int i = 0; i < method.lineNumber - 1; i++) offset = text.indexOf('\n', offset) + 1
        text = text.substring(0, offset) + annotation + '\n' + text.substring(offset)
    }
    text
}
boolean compiled = false
Map after
for (int pass = 1; pass <= 12; pass++) {
    String candidate = annotate(selected)
    def unit = unitFor(candidate)
    try {
        unit.compile(Phases.CLASS_GENERATION)
        after = metrics(unit.classes)
        new File(output, 'candidate-inlined.groovy').text = candidate
        compiled = true
        break
    } catch (org.codehaus.groovy.control.MultipleCompilationErrorsException error) {
        def positions = unitFor(candidate)
        positions.compile(Phases.CONVERSION)
        List positioned = positions.AST.classes.find { it.script }.methods.findAll { it.lineNumber > 0 }
        Set remove = [] as Set
        error.errorCollector.errors.each { finding ->
            if (!(finding instanceof SyntaxErrorMessage)) throw error
            def syntax = finding.cause
            def method = positioned.find { it.lineNumber <= syntax.startLine && syntax.startLine <= it.lastLineNumber }
            if (method == null || !selected.contains(method.name)) throw error
            remove.add(method.name)
            failures << [pass: pass, method: method.name, message: syntax.message]
        }
        assert remove: 'Compiler failed without a removable new candidate'
        selected.removeAll(remove)
        println "${args[3]} pass ${pass}: removed ${remove.size()}, retained ${selected.size()} new candidates"
    }
}
assert compiled: 'Full-source discovery did not converge within 12 passes'
def previous = unitFor(source)
previous.compile(Phases.CLASS_GENERATION)
Map before = metrics(previous.classes)
List names = (retained + selected).sort()
new File(output, 'selected.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(names)) + '\n'
new File(output, 'discovery.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
    groovy: GroovySystem.version, scriptBase: args[2], retained: retained.size(), added: selected.size(),
    selected: names, before: before, after: after,
    compilerFailures: failures, boundaries: records.values().findAll { it.reasons }
])) + '\n'
println "${args[3]}: full source compiles with ${retained.size()} existing + ${selected.size()} connected candidates"
println JsonOutput.prettyPrint(JsonOutput.toJson([before: before, after: after]))
