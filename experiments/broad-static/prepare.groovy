// GitHub Actions only: generate source, compile it, never execute the app DSL.
import groovy.json.JsonOutput
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.*
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.classgen.VariableScopeVisitor
import org.codehaus.groovy.classgen.ReturnAdder
import org.codehaus.groovy.antlr.parser.GroovyLexer
import org.codehaus.groovy.antlr.parser.GroovyTokenTypes
import groovyjarjarasm.asm.*
import java.security.MessageDigest

assert System.getenv('GITHUB_ACTIONS') == 'true': 'Run preparation in GitHub Actions only'
assert args.length == 4: 'prepare.groovy <root> <output> <base-class> <flavor>'
File root = new File(args[0]).canonicalFile
File output = new File(args[1], args[3])
output.mkdirs()
Class resolver = new GroovyClassLoader().parseClass(new File(root, 'src/test/groovy/support/IncludeResolver.groovy'))
File app = new File(root, 'hubitat-mcp-server.groovy')
Map resolved = resolver.resolveWithOrigins(app.getText('UTF-8'), new File(root, 'libraries'), app)
String source = resolved.source
String prefix = '__bat529Static'
assert !source.contains(prefix): 'Preparation has already been applied'

def sha = { byte[] bytes -> MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
def unitFor = { String text ->
    CompilerConfiguration config = new CompilerConfiguration()
    config.scriptBaseClass = args[2]
    config.tolerance = 10000
    CompilationUnit unit = new CompilationUnit(config)
    unit.addSource('hubitat-mcp-server.groovy', text)
    unit
}
def parsed = unitFor(source)
parsed.compile(Phases.CONVERSION)
SourceUnit su = parsed.iterator().next()
ClassNode scriptClass = parsed.AST.classes.find { it.script }
new VariableScopeVisitor(su).visitClass(scriptClass)
List methods = scriptClass.methods.findAll { it.lineNumber > 0 && !it.synthetic }
Map byName = methods.groupBy { it.name }
List lineStarts = [0]
for (int n = 0; n < source.length(); n++) if (source.charAt(n) == '\n') lineStarts << n + 1
def offset = { int line, int column ->
    assert line > 0 && line <= lineStarts.size() && column > 0
    lineStarts[line - 1] + column - 1
}
def rawSpan = { ASTNode node ->
    if (node.lineNumber < 1 || node.lastLineNumber < 1 || node.columnNumber < 1 || node.lastColumnNumber < 1) return null
    int first = offset(node.lineNumber, node.columnNumber)
    int last = offset(node.lastLineNumber, node.lastColumnNumber)
    first <= last && last <= source.length() ? [first, last] : null
}
def span = rawSpan
// AST spans omit different layers of receiver parentheses in 2.4 and 3.0.
// Pair lexical punctuation, ignoring strings/comments and GString contents via
// Groovy's lexer, then derive member-call bounds from the actual method token.
Map punctuation = [:], closing = [:]
List opens = []
def lexer = new GroovyLexer(new StringReader(source)).plumb()
Map closeTypes = [(GroovyTokenTypes.RPAREN): GroovyTokenTypes.LPAREN,
                  (GroovyTokenTypes.RBRACK): GroovyTokenTypes.LBRACK,
                  (GroovyTokenTypes.RCURLY): GroovyTokenTypes.LCURLY]
while (true) {
    def token = lexer.nextToken()
    if (token.type == GroovyTokenTypes.EOF) break
    if (token.type in closeTypes.values()) opens << [type: token.type, start: offset(token.line, token.column)]
    else if (closeTypes.containsKey(token.type)) {
        assert opens && opens[-1].type == closeTypes[token.type]: 'Unmatched lexical punctuation'
        Map open = opens.remove(opens.size() - 1)
        int close = offset(token.line, token.column)
        punctuation[open.start] = close + 1
        closing[close] = open.start
    }
}
assert !opens: 'Unclosed lexical punctuation'
NavigableMap openingPairs = new TreeMap(punctuation)
NavigableMap closingPairs = new TreeMap(closing)
def balancedRange = { List initial ->
    if (!initial || initial[0] < 0 || initial[1] < initial[0] || initial[1] > source.length()) return null
    int start = initial[0], end = initial[1]
    boolean changed = true
    while (changed && start < end) {
        int priorStart = start, priorEnd = end
        // Every delimiter inside a complete expression must have its partner
        // inside too. AST child ends often omit a parenthesized RHS's final ')'.
        openingPairs.subMap(start, true, end, false).values().each { close -> end = Math.max(end, (int) close) }
        closingPairs.subMap(start, true, end, false).values().each { open -> start = Math.min(start, (int) open) }
        changed = start != priorStart || end != priorEnd
    }
    [start, end]
}
def skipTrivia = { int start ->
    int at = start
    while (at < source.length()) {
        if (Character.isWhitespace(source.charAt(at))) at++
        else if (source.startsWith('//', at)) {
            int end = source.indexOf('\n', at + 2); at = end < 0 ? source.length() : end + 1
        } else if (source.startsWith('/*', at)) {
            int end = source.indexOf('*/', at + 2); assert end >= 0; at = end + 2
        } else break
    }
    at
}
def memberBounds = { MethodCallExpression call ->
    List name = rawSpan(call.method), object = span(call.objectExpression)
    if (!name || !object || source.substring(name[0], name[1]) != call.methodAsString) return null
    int dot = name[0]
    while (dot > 0 && Character.isWhitespace(source.charAt(dot - 1))) dot--
    if (dot == 0 || source.charAt(dot - 1) != '.') return null
    dot--
    if (dot > 0 && source.charAt(dot - 1) == '?') dot--
    int receiverEnd = dot
    while (receiverEnd > 0 && Character.isWhitespace(source.charAt(receiverEnd - 1))) receiverEnd--
    int receiverStart = object[0]
    if (receiverStart >= receiverEnd) return null
    // Include every enclosing pair that closes before the dispatch operator.
    // Pairs wrapping the entire call close later, so they stay outside the edit.
    Integer enclosing = closing[receiverEnd - 1]
    if (enclosing != null && enclosing < receiverStart) receiverStart = enclosing
    int next = skipTrivia(name[1])
    if (next >= source.length() || !(source.charAt(next) in ['(' as char, '{' as char]) || !punctuation.containsKey(next)) return null
    int end = punctuation[next]
    if (source.charAt(next) == '(') {
        int following = skipTrivia(end)
        if (following < source.length() && source.charAt(following) == '{' && call.arguments instanceof TupleExpression && call.arguments.expressions.any { it instanceof ClosureExpression }) {
            if (!punctuation.containsKey(following)) return null
            end = punctuation[following]
        }
    }
    [range: [receiverStart, end], receiver: [receiverStart, receiverEnd], suffix: [name[1], end]]
}
Map expressionSpans = new IdentityHashMap()
span = { ASTNode node ->
    if (expressionSpans.containsKey(node)) return expressionSpans[node]
    List r = rawSpan(node)
    // Several parser nodes are positioned at the operator, not the complete
    // expression: '+' for BinaryExpression, '.' for PropertyExpression, etc.
    // Reconstruct their source interval recursively from actual operands.
    if (node instanceof VariableExpression && node.lineNumber > 0 && node.columnNumber > 0 && !node.thisExpression && !node.superExpression) {
        int start = offset(node.lineNumber, node.columnNumber)
        if (source.startsWith(node.name, start)) r = [start, start + node.name.length()]
    } else if (node instanceof BinaryExpression) {
        List a = span(node.leftExpression), b = span(node.rightExpression)
        if (a && b && a[0] <= b[0]) {
            r = [a[0], b[1]]
            if (node.operation.text == '[') {
                int open = source.indexOf('[', a[1])
                if (open >= 0 && open <= b[0] && punctuation[open] >= b[1]) r[1] = punctuation[open]
            }
        } else r = null
    } else if (node instanceof PropertyExpression) {
        List a = span(node.objectExpression), b = span(node.property)
        r = a && b && a[0] <= b[0] ? [a[0], Math.max(a[1], b[1])] : null
    } else if (node instanceof TernaryExpression) {
        List a = span(node.booleanExpression.expression), b = span(node.trueExpression), c = span(node.falseExpression)
        r = a && b && c ? [[a[0], b[0], c[0]].min(), [a[1], b[1], c[1]].max()] : null
    } else if (node instanceof BooleanExpression) {
        r = span(node.expression)
    } else if (node instanceof MethodCallExpression) {
        if (!node.implicitThis) {
            Map bounds = memberBounds(node)
            r = bounds?.range
        } else {
            List name = rawSpan(node.method)
            if (name) {
                int open = skipTrivia(name[1])
                if (open < source.length() && source.charAt(open) == '(' && punctuation.containsKey(open)) r = [name[0], punctuation[open]]
            }
        }
    } else if (node instanceof ListExpression || node instanceof MapExpression || node instanceof ClosureExpression) {
        if (r && punctuation.containsKey(r[0])) r = [r[0], punctuation[r[0]]]
    } else if (node instanceof PrefixExpression || node instanceof PostfixExpression) {
        List child = span(node.expression)
        if (child && r) r = [Math.min(child[0], r[0]), Math.max(child[1], r[1])]
    }
    if (node instanceof Expression && r) r = balancedRange(r)
    if (r && (r[0] < 0 || r[1] < r[0] || r[1] > source.length())) r = null
    expressionSpans[node] = r
    r
}
def typeName = { ClassNode node ->
    String name = node.name
    if (name in ['Map', 'List', 'Set', 'Collection', 'Iterable', 'Iterator']) {
        return name == 'Iterable' ? 'java.lang.Iterable' : 'java.util.' + name
    }
    if (name in ['Object', 'String', 'Boolean', 'Number', 'Integer', 'Long', 'Double', 'Float', 'CharSequence']) return 'java.lang.' + name
    name
}
Set safeTypes = ['java.lang.Object', 'java.lang.String', 'java.lang.Boolean', 'java.lang.Number',
                 'java.lang.Integer', 'java.lang.Long', 'java.lang.Double', 'java.lang.Float',
                 'java.lang.CharSequence', 'java.lang.Iterable', 'java.util.Map', 'java.util.List',
                 'java.util.Set', 'java.util.Collection', 'java.util.Iterator',
                 'boolean', 'byte', 'short', 'int', 'long', 'float', 'double', 'char', 'void'] as Set
Map contracts = [:]
methods.each { method ->
    String name = typeName(method.returnType)
    if (safeTypes.contains(name) && name != 'java.lang.Object' && byName[method.name].every { typeName(it.returnType) == name }) contracts[method.name] = [type: name, fields: [:], evidence: 'declared']
}

// Contracts are inferred only when every return has the same runtime shape.
// Object/GString and mixed-type returns never acquire a narrower public signature.
Map localsByMethod = [:], returnsByMethod = [:], expressionsByMethod = [:], writesByMethod = [:]
methods.each { method ->
    Map locals = [:]
    Set reassigned = [] as Set
    List expressions = [], returns = [], writes = []
    int closureDepth = 0
    method.code.visit(new ClassCodeVisitorSupport() {
        protected SourceUnit getSourceUnit() { su }
        void visitClosureExpression(ClosureExpression e) {
            closureDepth++; super.visitClosureExpression(e); closureDepth--
        }
        void visitDeclarationExpression(DeclarationExpression e) {
            if (!e.multipleAssignmentDeclaration && closureDepth == 0) locals[e.variableExpression.name] = [expression: e.rightExpression, binding: e.variableExpression]
            super.visitDeclarationExpression(e)
        }
        void visitMethodCallExpression(MethodCallExpression e) { expressions << e; super.visitMethodCallExpression(e) }
        void visitPropertyExpression(PropertyExpression e) { expressions << e; super.visitPropertyExpression(e) }
        void visitBinaryExpression(BinaryExpression e) {
            expressions << e
            if (e instanceof DeclarationExpression || e.operation.text in ['=', '+=', '-=', '*=', '/=', '%=', '<<=', '>>=']) {
                writes << e.leftExpression
                if (!(e instanceof DeclarationExpression) && e.leftExpression instanceof VariableExpression) reassigned.add(e.leftExpression.name)
            }
            super.visitBinaryExpression(e)
        }
        void visitVariableExpression(VariableExpression e) { expressions << e; super.visitVariableExpression(e) }
        void visitCastExpression(CastExpression e) { expressions << e; super.visitCastExpression(e) }
        void visitTernaryExpression(TernaryExpression e) { expressions << e; super.visitTernaryExpression(e) }
        void visitPrefixExpression(PrefixExpression e) {
            expressions << e
            writes << e.expression
            if (e.expression instanceof VariableExpression) reassigned.add(e.expression.name)
            super.visitPrefixExpression(e)
        }
        void visitPostfixExpression(PostfixExpression e) {
            expressions << e
            writes << e.expression
            if (e.expression instanceof VariableExpression) reassigned.add(e.expression.name)
            super.visitPostfixExpression(e)
        }
        void visitReturnStatement(ReturnStatement s) {
            if (closureDepth == 0) returns << s.expression
            super.visitReturnStatement(s)
        }
    })
    reassigned.each { locals.remove(it) }
    localsByMethod[method.name] = locals
    expressionsByMethod[method.name] = expressions
    writesByMethod[method.name] = writes
    // ReturnAdder inserts the implicit returns into this CI-only AST.
    new ReturnAdder().visitMethod(method)
    returns = []
    closureDepth = 0
    method.code.visit(new ClassCodeVisitorSupport() {
        protected SourceUnit getSourceUnit() { su }
        void visitClosureExpression(ClosureExpression e) {
            closureDepth++; super.visitClosureExpression(e); closureDepth--
        }
        void visitReturnStatement(ReturnStatement s) {
            if (closureDepth == 0) returns << s.expression
            super.visitReturnStatement(s)
        }
    })
    returnsByMethod[method.name] = returns
}
def infer
infer = { Expression e, Map locals, Set seen ->
    if (e instanceof ConstantExpression) {
        if (e.value == null) return [type: 'null', fields: [:]]
        String type = e.value.getClass().name
        return safeTypes.contains(type) ? [type: type, fields: [:]] : null
    }
    if (e instanceof ListExpression) return [type: 'java.util.List', fields: [:]]
    if (e instanceof MapExpression) {
        Map fields = [:]
        e.mapEntryExpressions.each { entry ->
            if (entry.keyExpression instanceof ConstantExpression) {
                Map shape = infer(entry.valueExpression, locals, seen)
                if (shape && shape.type != 'null') fields[entry.keyExpression.value.toString()] = shape
            }
        }
        return [type: 'java.util.Map', fields: fields]
    }
    if (e instanceof CastExpression) {
        String type = typeName(e.type)
        return safeTypes.contains(type) ? [type: type, fields: [:]] : null
    }
    if (e instanceof VariableExpression) {
        String type = typeName(e.type)
        if (type != 'java.lang.Object' && safeTypes.contains(type)) return [type: type, fields: [:]]
        if (locals.containsKey(e.name) && !seen.contains(e.name) && e.accessedVariable.is(locals[e.name].binding)) return infer(locals[e.name].expression, locals, seen + e.name)
        return null
    }
    if (e instanceof MethodCallExpression && (e.implicitThis || e.objectExpression instanceof VariableExpression && e.objectExpression.thisExpression)) {
        return contracts[e.methodAsString]
    }
    if (e instanceof PropertyExpression) {
        Map shape = infer(e.objectExpression, locals, seen)
        return shape?.fields?.get(e.propertyAsString)
    }
    if (e instanceof TernaryExpression) {
        Map a = infer(e.trueExpression, locals, seen), b = infer(e.falseExpression, locals, seen)
        if (a?.type == 'null') return b
        if (b?.type == 'null') return a
        if (a && b && a.type == b.type) return [type: a.type, fields: a.fields.findAll { k, v -> b.fields[k] == v }]
    }
    return null
}
for (int pass = 0; pass < 8; pass++) {
    methods.findAll { byName[it.name].size() == 1 }.each { method ->
        List shapes = returnsByMethod[method.name].collect { infer(it, localsByMethod[method.name], [] as Set) }
        if (!shapes || shapes.any { it == null }) return
        List concrete = shapes.findAll { it.type != 'null' }
        if (!concrete || concrete*.type.unique().size() != 1) return
        // Nullable primitive inference would introduce an unboxing behavior change.
        String type = concrete[0].type
        if (shapes.size() != concrete.size() && !type.contains('.')) return
        Map fields = concrete[0].fields.findAll { k, v -> concrete.every { it.fields[k] == v } }
        if (!contracts.containsKey(method.name)) contracts[method.name] = [type: type, fields: fields, evidence: 'all-return-shapes']
        else if (contracts[method.name].type == type) contracts[method.name].fields = fields
    }
}

List boundaries = []
Map plans = [:]
Set collectionOperations = ['each', 'collect', 'collectEntries', 'findAll', 'find', 'any', 'every', 'inject'] as Set
Map platformTypes = [now: 'long', getChildDevices: 'java.util.List', getChildApps: 'java.util.List',
                     getHubIP: 'java.lang.String', getLocationMode: 'java.lang.String']
Set magic = ['metaClass', 'delegate', 'owner', 'thisObject', 'resolveStrategy', 'propertyMissing',
             'methodMissing', 'invokeMethod', 'getClass', 'getMetaClass', 'use', 'mixin', 'rehydrate'] as Set
Set dsl = ['definition', 'preferences', 'mappings', 'dynamicPage', 'section', 'input', 'paragraph',
           'href', 'app', 'label', 'mode', 'page', 'path', 'action'] as Set

def capturesFor = { Expression expression, boolean propertyWrites ->
    List hazards = [], captures = []
    expression.visit(new ClassCodeVisitorSupport() {
        protected SourceUnit getSourceUnit() { su }
        void visitClosureExpression(ClosureExpression e) { hazards << 'closure extraction'; super.visitClosureExpression(e) }
        void visitDeclarationExpression(DeclarationExpression e) { hazards << 'declaration'; super.visitDeclarationExpression(e) }
        void visitBinaryExpression(BinaryExpression e) {
            if (e.operation.text in ['=', '+=', '-=', '*=', '/=', '%=', '<<=', '>>=']) {
                if (!propertyWrites || e.leftExpression instanceof VariableExpression && !(e.leftExpression.accessedVariable instanceof DynamicVariable) && !(e.leftExpression.accessedVariable instanceof FieldNode)) hazards << 'lexical write'
            }
            super.visitBinaryExpression(e)
        }
        void visitPrefixExpression(PrefixExpression e) {
            if (!propertyWrites || e.expression instanceof VariableExpression && !(e.expression.accessedVariable instanceof DynamicVariable) && !(e.expression.accessedVariable instanceof FieldNode)) hazards << 'lexical write'
            super.visitPrefixExpression(e)
        }
        void visitPostfixExpression(PostfixExpression e) {
            if (!propertyWrites || e.expression instanceof VariableExpression && !(e.expression.accessedVariable instanceof DynamicVariable) && !(e.expression.accessedVariable instanceof FieldNode)) hazards << 'lexical write'
            super.visitPostfixExpression(e)
        }
        void visitVariableExpression(VariableExpression e) {
            // The boundary stays on the same script instance. Script properties
            // (including state/atomicState) must not become captured snapshots.
            if (e.superExpression) hazards << 'base dispatch'
            else if (e.thisExpression || e.accessedVariable instanceof DynamicVariable || e.accessedVariable instanceof FieldNode) { }
            else captures << e.name
            super.visitVariableExpression(e)
        }
    })
    [hazards: hazards.unique(), captures: captures.unique().sort()]
}
def storeReference = { Expression expression ->
    boolean found = false
    expression.visit(new ClassCodeVisitorSupport() {
        protected SourceUnit getSourceUnit() { su }
        void visitVariableExpression(VariableExpression e) {
            if (e.name in ['state', 'atomicState'] && e.accessedVariable instanceof DynamicVariable) found = true
            super.visitVariableExpression(e)
        }
        void visitPropertyExpression(PropertyExpression e) {
            if (e.propertyAsString in ['state', 'atomicState'] && e.objectExpression instanceof VariableExpression && e.objectExpression.thisExpression) found = true
            super.visitPropertyExpression(e)
        }
    })
    found
}

def originalEdit = { int start, int end, String replacement ->
    Map origin = resolved.origins.find { start >= it.start && start < it.end && end <= it.end }
    assert origin: "Edit crosses an include origin at ${start}..${end}"
    [path: root.toPath().relativize(origin.original.file.toPath()).toString().replace('\\', '/'),
     start: origin.offset + start - origin.start, end: origin.offset + end - origin.start,
     before: source.substring(start, end), replacement: replacement]
}
methods.each { method ->
    if (method.annotations.any { it.classNode.name == 'groovy.transform.CompileStatic' }) return
    List range = span(method)
    if (!range) { boundaries << [method: method.name, reason: 'missing method span']; return }
    String body = source.substring(range[0], range[1])
    List reasons = []
    if (byName[method.name].size() != 1) reasons << 'overloaded method'
    if (magic.any { body =~ ('\\b' + it + '\\b') }) reasons << 'closure identity or introspection'
    List calls = expressionsByMethod[method.name].findAll { it instanceof MethodCallExpression }
    if (calls.any { it.implicitThis && dsl.contains(it.methodAsString) }) reasons << 'delegated UI DSL'
    if (body =~ /\bsuper\s*\./) reasons << 'explicit base-class dispatch'
    if (reasons) { boundaries << [method: method.name, reasons: reasons]; return }
    Map plan = [method: method.name, range: range, edits: [], helpers: [:], islands: [], failures: [], storeBoundaries: [], explicitReturnType: null, explicitLocalTypes: []]
    int line = lineStarts[method.lineNumber - 1]
    plan.edits << [start: line, end: line, replacement: '@groovy.transform.CompileStatic\n']
    // A def return can be made explicit only after all runtime return shapes
    // agree. Mixed, unknown, GString, and meaningful implicit returns remain intact.
    Map contract = contracts[method.name]
    if (contract?.evidence == 'all-return-shapes' && contract.type != 'java.lang.Object') {
        def header = (body =~ ('^(?:(?:private|protected|public|static|final)\\s+)*def(?=\\s+' + java.util.regex.Pattern.quote(method.name) + '\\s*\\()'))
        if (header.find()) {
            int keyword = range[0] + header.group().lastIndexOf('def')
            plan.edits << [start: keyword, end: keyword + 3, replacement: contract.type]
            plan.explicitReturnType = contract.type
        }
    }
    // Make proven, unreassigned local contracts explicit. Only a declaration
    // beginning its own source line is rewritten; same-name closure locals and
    // tuple declarations are deliberately not handled by a textual substitution.
    localsByMethod[method.name].each { local, record ->
        Map shape = infer(record.expression, localsByMethod[method.name], [] as Set)
        if (!shape || !(shape.type in ['java.util.Map', 'java.util.List', 'java.util.Set', 'java.lang.String'])) return
        int start = lineStarts[record.binding.lineNumber - 1]
        int end = source.indexOf('\n', start)
        if (end < 0) end = source.length()
        String lineText = source.substring(start, end)
        def declaration = (lineText =~ ('^(\\s*)def(?=\\s+' + java.util.regex.Pattern.quote(local) + '\\s*=)'))
        if (declaration.find()) {
            int keyword = start + declaration.group(1).length()
            plan.edits << [start: keyword, end: keyword + 3, replacement: shape.type]
            plan.explicitLocalTypes << [name: local, type: shape.type]
        }
    }
    // Preserve complete property/index setter operations in dynamic code. This
    // keeps their original evaluation order and the platform's proxy setter;
    // neither the store object nor its contents are converted to an ordinary Map.
    List storeWrites = expressionsByMethod[method.name].findAll { expression ->
        boolean write = expression instanceof BinaryExpression && !(expression instanceof DeclarationExpression) && expression.operation.text in ['=', '+=', '-=', '*=', '/=', '%=', '<<=', '>>='] ||
                        expression instanceof PrefixExpression || expression instanceof PostfixExpression
        write && storeReference(expression) && span(expression)
    }.sort { expression -> List r = span(expression); -(r[1] - r[0]) }
    storeWrites.each { expression ->
        List r = span(expression)
        if (plan.edits.any { it.start < r[1] && it.end > r[0] && it.start != it.end }) return
        Map capture = capturesFor(expression, true)
        if (capture.hazards) { plan.storeBoundaries << [line: expression.lineNumber, converted: false, reasons: capture.hazards]; return }
        String bridgeName = prefix + 'Store_' + method.name + '_' + plan.storeBoundaries.size()
        String text = source.substring(r[0], r[1])
        plan.helpers[bridgeName] = "@groovy.transform.CompileDynamic\nprivate Object ${bridgeName}(${capture.captures.collect { 'Object ' + it }.join(', ')}) {\n    return ${text}\n}\n"
        plan.edits << [start: r[0], end: r[1], replacement: bridgeName + '(' + capture.captures.join(', ') + ')']
        plan.storeBoundaries << [line: expression.lineNumber, converted: true, operation: 'original setter expression', expression: text]
    }
    // Reads use the original script binding dynamically. In particular, do not
    // cast atomicState/state to Map or copy/cache either persistence proxy.
    expressionsByMethod[method.name].findAll { expression ->
        expression instanceof VariableExpression && expression.name in ['state', 'atomicState', 'settings', 'location', 'app', 'request', 'params', 'log', 'parent', 'device'] && expression.accessedVariable instanceof DynamicVariable
    }.each { expression ->
        List r = span(expression)
        if (!r || plan.edits.any { it.start < r[1] && it.end > r[0] && it.start != it.end }) return
        if (writesByMethod[method.name].any { target -> List w = span(target); w && r[0] >= w[0] && r[1] <= w[1] }) return
        String bridgeName = prefix + 'Binding_' + expression.name
        plan.helpers[bridgeName] = "@groovy.transform.CompileDynamic\nprivate Object ${bridgeName}() {\n    return ${expression.name}\n}\n"
        plan.edits << [start: r[0], end: r[1], replacement: bridgeName + '()']
        plan.storeBoundaries << [line: expression.lineNumber, converted: true, operation: 'original script binding read', binding: expression.name]
    }
    // Keep implicit script calls dynamically dispatched, including call sites
    // inside ordinary callbacks. Existing closure source and captures stay put.
    Set lexicalCalls = (localsByMethod[method.name].keySet() + method.parameters*.name) as Set
    calls.findAll { (it.implicitThis || it.objectExpression instanceof VariableExpression && it.objectExpression.thisExpression) && it.methodAsString && !lexicalCalls.contains(it.methodAsString) && !it.safe && !it.spreadSafe }.each { call ->
        if (!(call.arguments instanceof TupleExpression) || call.arguments.expressions.any { it instanceof SpreadExpression }) return
        List location = span(call)
        if (!location) return
        String name = call.methodAsString
        String text = source.substring(location[0], location[1])
        def token = (text =~ ('^(?:this\\s*\\.\\s*)?' + java.util.regex.Pattern.quote(name) + '(?=\\s*\\()'))
        if (!token.find()) return
        int tokenEnd = location[0] + token.group().length()
        if (plan.edits.any { it.start < tokenEnd && it.end > location[0] && it.start != it.end }) return
        int arity = call.arguments.expressions.size()
        String returns = contracts[name]?.type ?: platformTypes[name] ?: 'java.lang.Object'
        // A dynamically called void method yields null. Keep that usable as an
        // Object even when a statically compiled caller assigns its result.
        if (returns == 'void') returns = 'java.lang.Object'
        boolean named = call.arguments.expressions.any { it instanceof NamedArgumentListExpression }
        String bridgeName = prefix + 'Call_' + (named ? name + '_' : '') + returns.replace('.', '_') + '_' + arity
        String params = (0..<arity).collect { 'Object p' + it }.join(', ')
        String values = (0..<arity).collect { 'p' + it }.join(', ')
        String invoke = named ? name + '(' + values + ')' : 'this."${target}"(' + values + ')'
        String result = returns == 'java.lang.Object' ? 'return ' + invoke : 'return (' + returns + ') (' + invoke + ')'
        String declaration = named ? params : 'String target' + (params ? ', ' + params : '')
        String helper = "@groovy.transform.CompileDynamic\nprivate ${returns} ${bridgeName}(${declaration}) {\n    ${result}\n}\n"
        plan.helpers[bridgeName] = helper
        if (named) {
            // Groovy moves named arguments to the first position. Keep this
            // exceptional bridge per target instead of inserting a String first.
            plan.edits << [start: location[0], end: tokenEnd, replacement: bridgeName]
        } else {
            int open = source.indexOf('(', tokenEnd)
            plan.edits << [start: location[0], end: open + 1,
                           replacement: bridgeName + "('" + name + "'" + (arity ? ', ' : '')]
        }
    }
    // Preserve runtime Map/List extension selection and two-argument Map
    // closure dispatch. The closures themselves keep their original lexical
    // location, owners, delegates, captures, and nonlocal writes.
    calls.findAll { !it.implicitThis && collectionOperations.contains(it.methodAsString) && !it.spreadSafe }.sort { call ->
        List r = span(call); r ? r[1] - r[0] : Integer.MAX_VALUE
    }.each { call ->
        if (!(call.arguments instanceof TupleExpression) || call.arguments.expressions.any { it instanceof SpreadExpression || it instanceof NamedArgumentListExpression }) return
        Map bounds = memberBounds(call)
        if (!bounds) return
        List r = bounds.range
        if (plan.edits.any { it.start < r[0] && it.end > r[0] || it.start < r[1] && it.end > r[1] }) return
        def rewrittenSlice = { List part ->
            String text = source.substring(part[0], part[1])
            plan.edits.findAll { it.start >= part[0] && it.end <= part[1] && it.start != it.end }.sort { -it.start }.each { edit ->
                text = text.substring(0, edit.start - part[0]) + edit.replacement + text.substring(edit.end - part[0])
            }
            text
        }
        String operation = call.methodAsString
        int arity = call.arguments.expressions.size()
        String returns = operation == 'collect' ? 'java.util.List' : operation == 'collectEntries' ? 'java.util.Map' :
                         operation in ['any', 'every'] ? 'boolean' : 'java.lang.Object'
        Map receiverShape = infer(call.objectExpression, localsByMethod[method.name], [] as Set)
        if (operation in ['each', 'findAll'] && receiverShape?.type in ['java.util.Map', 'java.util.List', 'java.util.Set']) returns = receiverShape.type
        String bridgeName = prefix + 'Dgm_' + operation + '_' + arity + '_' + (call.safe ? 'Safe_' : '') + returns.replace('.', '_')
        String params = (0..<arity).collect { 'Object p' + it }.join(', ')
        String values = (0..<arity).collect { 'p' + it }.join(', ')
        String invoke = 'receiver' + (call.safe ? '?.' : '.') + operation + '(' + values + ')'
        // Null-safe any/every must retain null rather than unbox to false.
        if (call.safe && returns == 'boolean') returns = 'java.lang.Boolean'
        String result = returns == 'java.lang.Object' ? invoke : '(' + returns + ') (' + invoke + ')'
        plan.helpers[bridgeName] = "@groovy.transform.CompileDynamic\nprivate ${returns} ${bridgeName}(Object receiver${params ? ', ' + params : ''}) {\n    return ${result}\n}\n"
        String receiverText = rewrittenSlice(bounds.receiver)
        String suffix = rewrittenSlice(bounds.suffix)
        String replacement
        if (suffix ==~ /(?s)^\s*\(.*$/) {
            boolean empty = suffix ==~ /(?s)^\s*\(\s*\).*/
            replacement = bridgeName + suffix.replaceFirst(/\(/, java.util.regex.Matcher.quoteReplacement('(' + receiverText + (empty ? '' : ', ')))
        } else if (suffix ==~ /(?s)^\s*\{.*$/) {
            // Keep the original trailing closure spelling; AST closure start
            // columns differ between Groovy parsers and may omit its brace.
            replacement = bridgeName + '(' + receiverText + ')' + suffix
        } else return
        // Offsets below map through replacements, so line count need not match.
        plan.edits.removeAll { it.start >= r[0] && it.end <= r[1] && it.start != it.end }
        plan.edits << [start: r[0], end: r[1], replacement: replacement]
    }
    plans[method.name] = plan
}
new File(output, 'inventory.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
    revision: System.getenv('GITHUB_SHA'), groovy: GroovySystem.version,
    declaredMethods: methods.size(), attempted: plans.keySet().sort(),
    contracts: contracts, boundaries: boundaries
])) + '\n'

def render = { Map selected ->
    // Collapsing a larger expression can make earlier read/call adapters unused.
    // Remove those per method so the measured and assembled helper sets agree.
    selected.values().each { plan ->
        String body = source.substring(plan.range[0], plan.range[1])
        plan.edits.findAll { it.start >= plan.range[0] && it.end <= plan.range[1] }.sort { -it.start }.each { edit ->
            body = body.substring(0, edit.start - plan.range[0]) + edit.replacement + body.substring(edit.end - plan.range[0])
        }
        plan.helpers = plan.helpers.findAll { name, code -> body.contains(name + '(') }
    }
    List edits = selected.values().collectMany { it.edits }.sort { a, b -> a.start <=> b.start ?: a.end <=> b.end }
    StringBuilder text = new StringBuilder()
    List mapping = []
    int previous = 0
    edits.each { edit ->
        assert edit.start >= previous: 'Overlapping source edits'
        int begin = text.length()
        text.append(source.substring(previous, edit.start))
        mapping << [start: begin, end: text.length(), original: previous, changed: false]
        begin = text.length()
        text.append(edit.replacement)
        mapping << [start: begin, end: text.length(), original: edit.start, changed: true]
        previous = edit.end
    }
    int begin = text.length()
    text.append(source.substring(previous))
    mapping << [start: begin, end: text.length(), original: previous, changed: false]
    Map helpers = [:]
    selected.values().each { helpers.putAll(it.helpers) }
    text.append('\n').append(helpers.keySet().sort().collect { helpers[it] }.join('\n'))
    [source: text.toString(), mapping: mapping, helpers: helpers]
}
def metrics = { classes ->
    Map result = [classes: classes.size(), totalBytes: 0, parentConstantPool: 0, parentMethods: 0,
                  largestMethodBytes: 0, largestMethod: null, dynamicCallSiteSlots: 0, dynamicDispatchInstructions: 0,
                  genericDynamicDispatchInstructions: 0]
    classes.each { generated ->
        byte[] bytes = generated.bytes
        result.totalBytes += bytes.length
        if (!generated.name.contains('$')) result.parentConstantPool = ((bytes[8] & 255) << 8) | (bytes[9] & 255)
        Map sizes = classFileSizes(generated.name, bytes)
        if (!generated.name.contains('$')) result.parentMethods = sizes.methodCount
        if (sizes.method.code > result.largestMethodBytes) {
            result.largestMethodBytes = sizes.method.code
            result.largestMethod = sizes.method.name
        }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                new MethodVisitor(Opcodes.ASM5) {
                    void visitLdcInsn(Object value) {
                        if (name.startsWith('$createCallSiteArray_') && value instanceof String) result.dynamicCallSiteSlots++
                    }
                    void visitMethodInsn(int opcode, String owner, String invoked, String invokedDescriptor, boolean itf) {
                        if (owner.startsWith('org/codehaus/groovy/runtime/callsite/')) result.dynamicDispatchInstructions++
                        if (owner == 'org/codehaus/groovy/runtime/ScriptBytecodeAdapter' && invoked.startsWith('invokeMethod')) result.genericDynamicDispatchInstructions++
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
    }
    result.totalDynamicDispatchInstructions = result.dynamicDispatchInstructions + result.genericDynamicDispatchInstructions
    result
}
def baselineUnit = unitFor(source)
baselineUnit.compile(Phases.CLASS_GENERATION)
Map before = metrics(baselineUnit.classes)
Map selected = new LinkedHashMap(plans)
Map candidate, after
boolean compiled = false
int compilePasses = 0
for (int pass = 1; pass <= 40; pass++) {
    compilePasses = pass
    candidate = render(selected)
    // Save before compilation and before attempting to reparse diagnostics.
    // A generated syntax failure must leave the exact failing source artifact.
    new File(output, 'candidate-inlined.groovy').text = candidate.source
    new File(output, 'candidate-pass.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([pass: pass, methods: selected.keySet().sort()])) + '\n'
    def unit = unitFor(candidate.source)
    try {
        unit.compile(Phases.CLASS_GENERATION)
        after = metrics(unit.classes)
        new File(output, 'compiled-metrics.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([before: before, after: after])) + '\n'
        assert after.parentConstantPool < 64000: "Candidate constant pool ${after.parentConstantPool} exceeds hub budget"
        assert after.parentMethods < 65535: "Candidate method count ${after.parentMethods} exceeds JVM limit"
        assert after.largestMethodBytes < 60000: "Candidate method ${after.largestMethod} uses ${after.largestMethodBytes} bytes"
        compiled = true
        break
    } catch (MultipleCompilationErrorsException error) {
        def positions = unitFor(candidate.source)
        positions.compile(Phases.CONVERSION)
        List positioned = positions.AST.classes.find { it.script }.methods.findAll { it.lineNumber > 0 }
        Map errors = [:].withDefault { [] }
        error.errorCollector.errors.each { finding ->
            if (!(finding instanceof SyntaxErrorMessage)) throw error
            def syntax = finding.cause
            def method = positioned.find { it.lineNumber <= syntax.startLine && syntax.startLine <= it.lastLineNumber }
            if (method == null || !selected.containsKey(method.name)) throw error
            errors[method.name] << [message: syntax.message, line: syntax.startLine, column: syntax.startColumn]
        }
        assert errors: 'Compilation failed without mapped candidate diagnostics'
        errors.each { name, findings ->
            Map plan = selected[name]
            boolean changed = false
            // The exact compiler location maps through replacements back to
            // original AST spans; extracting reads never copies lexical writes.
            findings.each { finding ->
                int point = 0
                for (int n = 1; n < finding.line; n++) point = candidate.source.indexOf('\n', point) + 1
                point += Math.max(0, finding.column - 1)
                Map map = candidate.mapping.find { point >= it.start && point < it.end }
                if (!map) return
                int original = map.changed ? map.original : map.original + point - map.start
                List choices = expressionsByMethod[name].findAll { expression ->
                    List r = span(expression)
                    r && r[0] <= original && original < r[1] && !(expression instanceof DeclarationExpression) && !(expression instanceof VariableExpression) &&
                        !writesByMethod[name].any { target ->
                            List write = span(target)
                            write && r[0] >= write[0] && r[1] <= write[1]
                        }
                }.sort { expression -> List r = span(expression); r[1] - r[0] }
                // Prefer a surrounding original expression when the location
                // points into an already-created binding/call adapter. Repeating
                // the same variable island would not improve its static type.
                if (map.changed) choices = choices.findAll { expression ->
                    List r = span(expression)
                    !plan.edits.any { it.start == r[0] && it.end == r[1] }
                }
                if (!choices) return
                Expression expression = choices[0]
                List r = span(expression)
                // Whole contained transformations can return to their exact
                // original spelling inside the dynamic island. Cross-boundary
                // overlaps cannot be composed safely and remain diagnosed.
                if (plan.edits.any { it.start < r[1] && it.end > r[0] && it.start != it.end && !(it.start >= r[0] && it.end <= r[1]) }) return
                String returns = infer(expression, localsByMethod[name], [] as Set)?.type ?: 'java.lang.Object'
                // An explicit initializer or return contract can constrain a
                // dynamic lookup without guessing from variable-name conventions.
                localsByMethod[name].each { local, initializer ->
                    if (initializer.expression.is(expression)) {
                        def localDecl = expressionsByMethod[name].find { it instanceof DeclarationExpression && !it.multipleAssignmentDeclaration && it.variableExpression.name == local }
                        if (localDecl && !localDecl.variableExpression.dynamicTyped) returns = typeName(localDecl.variableExpression.type)
                    }
                }
                if (returnsByMethod[name].any { it.is(expression) } && contracts[name]) returns = contracts[name].type
                if (!safeTypes.contains(returns) || returns in ['void', 'null']) return
                def fragment = { List part ->
                    if (!part) return null
                    String text = source.substring(part[0], part[1])
                    plan.edits.findAll { it.start >= part[0] && it.end <= part[1] && it.start != it.end }.sort { -it.start }.each { edit ->
                        text = text.substring(0, edit.start - part[0]) + edit.replacement + text.substring(edit.end - part[0])
                    }
                    text
                }
                def literal = { String value -> "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'" }
                String sharedName, sharedCode, sharedReplacement
                if (expression instanceof PropertyExpression && !expression.spreadSafe && expression.propertyAsString) {
                    List receiver = span(expression.objectExpression)
                    if (!receiver) return
                    sharedName = prefix + 'Property_' + (expression.safe ? 'Safe_' : '') + returns.replace('.', '_')
                    String read = 'receiver' + (expression.safe ? '?.' : '.') + '"${key}"'
                    String result = returns == 'java.lang.Object' ? read : '(' + returns + ') (' + read + ')'
                    sharedCode = "@groovy.transform.CompileDynamic\nprivate ${returns} ${sharedName}(Object receiver, String key) {\n    return ${result}\n}\n"
                    sharedReplacement = sharedName + '(' + fragment(receiver) + ', ' + literal(expression.propertyAsString) + ')'
                } else if (expression instanceof MethodCallExpression && !expression.implicitThis && expression.methodAsString && !expression.spreadSafe && expression.arguments instanceof TupleExpression && !expression.arguments.expressions.any { it instanceof SpreadExpression || it instanceof NamedArgumentListExpression }) {
                    Map bounds = memberBounds(expression)
                    if (!bounds) return
                    int arity = expression.arguments.expressions.size()
                    sharedName = prefix + 'ReceiverCall_' + (expression.safe ? 'Safe_' : '') + returns.replace('.', '_') + '_' + arity
                    String params = (0..<arity).collect { 'Object p' + it }.join(', ')
                    String values = (0..<arity).collect { 'p' + it }.join(', ')
                    String call = 'receiver' + (expression.safe ? '?.' : '.') + '"${target}"(' + values + ')'
                    String result = returns == 'java.lang.Object' ? call : '(' + returns + ') (' + call + ')'
                    sharedCode = "@groovy.transform.CompileDynamic\nprivate ${returns} ${sharedName}(Object receiver, String target${params ? ', ' + params : ''}) {\n    return ${result}\n}\n"
                    String suffix = fragment(bounds.suffix)
                    String first = fragment(bounds.receiver) + ', ' + literal(expression.methodAsString)
                    if (suffix ==~ /(?s)^\s*\(.*$/) {
                        boolean empty = suffix ==~ /(?s)^\s*\(\s*\).*/
                        sharedReplacement = sharedName + suffix.replaceFirst(/\(/, java.util.regex.Matcher.quoteReplacement('(' + first + (empty ? '' : ', ')))
                    } else if (suffix ==~ /(?s)^\s*\{.*$/) sharedReplacement = sharedName + '(' + first + ')' + suffix
                    else return
                } else if (expression instanceof BinaryExpression && expression.operation.text in ['+', '-', '*', '/', '%', '==', '!=', '<', '>', '<=', '>=', '<=>', '[']) {
                    List a = span(expression.leftExpression), b = span(expression.rightExpression)
                    if (!a || !b) return
                    Map operations = ['+': 'Plus', '-': 'Minus', '*': 'Multiply', '/': 'Divide', '%': 'Modulo',
                                      '==': 'Equal', '!=': 'NotEqual', '<': 'Less', '>': 'Greater', '<=': 'LessEqual',
                                      '>=': 'GreaterEqual', '<=>': 'Compare', '[': 'GetAt']
                    sharedName = prefix + 'Binary_' + operations[expression.operation.text] + '_' + returns.replace('.', '_')
                    String operation = expression.operation.text == '[' ? 'left[right]' : 'left ' + expression.operation.text + ' right'
                    String result = returns == 'java.lang.Object' ? operation : '(' + returns + ') (' + operation + ')'
                    sharedCode = "@groovy.transform.CompileDynamic\nprivate ${returns} ${sharedName}(Object left, Object right) {\n    return ${result}\n}\n"
                    sharedReplacement = sharedName + '(' + fragment(a) + ', ' + fragment(b) + ')'
                }
                if (sharedName) {
                    plan.helpers[sharedName] = sharedCode
                    plan.edits.removeAll { it.start >= r[0] && it.end <= r[1] && it.start != it.end }
                    plan.edits << [start: r[0], end: r[1], replacement: sharedReplacement]
                    plan.islands << [line: expression.lineNumber, type: returns, shared: true, expression: source.substring(r[0], r[1])]
                    changed = true
                    return
                }
                Map capture = capturesFor(expression, false)
                if (capture.hazards) return
                List captures = capture.captures
                String bridgeName = prefix + 'Expr_' + name + '_' + plan.islands.size()
                String text = source.substring(r[0], r[1])
                String result = returns == 'java.lang.Object' ? text : '(' + returns + ') (' + text + ')'
                plan.helpers[bridgeName] = "@groovy.transform.CompileDynamic\nprivate ${returns} ${bridgeName}(${captures.collect { 'Object ' + it }.join(', ')}) {\n    return ${result}\n}\n"
                String replacement = bridgeName + '(' + captures.join(', ') + ')' + '\n' * text.count('\n')
                plan.edits.removeAll { it.start >= r[0] && it.end <= r[1] && it.start != it.end }
                plan.edits << [start: r[0], end: r[1], replacement: replacement]
                plan.islands << [line: expression.lineNumber, type: returns, captures: captures, expression: text]
                changed = true
            }
            plan.failures.addAll(findings)
            if (!changed) {
                boundaries << [method: name, reason: 'unresolved static operation after safe adapters', failures: plan.failures]
                selected.remove(name)
            }
        }
        new File(output, 'progress.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
            pass: pass, selected: selected.keySet().sort(), boundaries: boundaries,
            diagnostics: errors, batchedDiagnostics: errors.values().sum { it.size() },
            dynamicExpressionBoundaries: selected.values().sum { it.islands.size() } ?: 0,
            metricStatus: 'Bytecode metrics require successful class generation; all diagnostics handled together'
        ])) + '\n'
        println "${args[3]} pass ${pass}: ${selected.size()} bodies, ${selected.values().sum { it.islands.size() } ?: 0} dynamic expression boundaries"
    }
}
assert compiled: 'Preparation did not converge within 40 full-source compiler passes'
List resultPlans = selected.values().collect { plan ->
    [method: plan.method, edits: plan.edits.collect { originalEdit(it.start, it.end, it.replacement) },
     helpers: plan.helpers, islands: plan.islands, storeBoundaries: plan.storeBoundaries, failures: plan.failures,
     explicitReturnType: plan.explicitReturnType, explicitLocalTypes: plan.explicitLocalTypes]
}
Map files = [:]
resolved.origins.each { origin ->
    String relative = root.toPath().relativize(origin.original.file.toPath()).toString().replace('\\', '/')
    files[relative] = sha(origin.original.file.bytes)
}
Map report = [revision: System.getenv('GITHUB_SHA'), groovy: GroovySystem.version, baseClass: args[2],
              files: files, selected: resultPlans, contracts: contracts, boundaries: boundaries,
              before: before, after: after, compilePasses: compilePasses, attempted: plans.size(),
              retainedStaticBodies: methods.count { method -> method.annotations.any { it.classNode.name == 'groovy.transform.CompileStatic' } },
              addedStaticBodies: selected.size(),
              explicitMethodReturns: resultPlans.count { it.explicitReturnType != null },
              explicitLocalVariables: resultPlans.sum { it.explicitLocalTypes.size() } ?: 0,
              helperCount: candidate.helpers.size(),
              behaviorValidation: 'REQUIRED: complete existing suites against assembled candidate; no benchmark claims']
new File(output, 'report.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n'
new File(output, 'candidate-inlined.groovy').text = candidate.source
println JsonOutput.prettyPrint(JsonOutput.toJson([selected: selected.size(), helpers: candidate.helpers.size(), before: before, after: after]))

// Same class-file size accounting as ci/groovy24-parse/parse_check.groovy,
// with method count added to expose adapter growth as well as code size.
Map classFileSizes(String className, byte[] b) {
    def u2 = { int o -> ((b[o] & 255) << 8) | (b[o + 1] & 255) }
    def u4 = { int o -> (((long) u2(o)) << 16) | u2(o + 2) }
    int cp = u2(8), p = 10
    Map utf8 = [:]
    for (int i = 1; i < cp; i++) {
        int tag = b[p] & 255
        if (tag == 1) { int len = u2(p + 1); utf8[i] = new String(b, p + 3, len, 'UTF-8'); p += 3 + len }
        else if (tag in [5, 6]) { p += 9; i++ }
        else if (tag in [3, 4, 9, 10, 11, 12, 17, 18]) p += 5
        else if (tag == 15) p += 4
        else p += 3
    }
    p += 6
    p += 2 + 2 * u2(p)
    int methodCount = 0
    def skipMembers = { boolean methods ->
        Map best = [name: null, code: 0]
        int count = u2(p); p += 2
        if (methods) methodCount = count
        count.times {
            String member = utf8[u2(p + 2)]
            int attrs = u2(p + 6); p += 8
            attrs.times {
                long len = u4(p + 2)
                if (methods && utf8[u2(p)] == 'Code') {
                    long code = u4(p + 10)
                    if (code > best.code) best = [name: className + '.' + member, code: code]
                }
                p += 6 + (int) len
            }
        }
        best
    }
    skipMembers(false)
    Map method = skipMembers(true)
    [methodCount: methodCount, method: method]
}
