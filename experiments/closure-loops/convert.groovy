// Execute only in GitHub Actions under stock Groovy 2.4.21.
import groovy.json.JsonOutput
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.*
import org.codehaus.groovy.classgen.VariableScopeVisitor
import java.security.MessageDigest

class EachLoopConverter {
    static final Set MAGIC = [
        'owner', 'delegate', 'thisObject', 'resolveStrategy', 'maximumNumberOfParameters',
        'parameterTypes', 'metaClass', 'super', 'getOwner', 'getDelegate', 'setDelegate',
        'getThisObject', 'getResolveStrategy', 'setResolveStrategy', 'getParameterTypes',
        'getMaximumNumberOfParameters', 'getMetaClass', 'setMetaClass', 'getClass',
        'rehydrate', 'dehydrate', 'curry', 'ncurry', 'rcurry', 'doCall', 'call',
        'getProperty', 'setProperty', 'invokeMethod', 'toString', 'hashCode', 'equals',
        'clone', 'respondsTo', 'hasProperty', 'use', 'mixin', 'is', 'asBoolean',
        'asType', 'isCase', 'iterator', 'getAt', 'putAt', 'with', 'tap',
        'identity', 'inspect', 'dump', 'each', 'collect', 'find', 'findAll',
        'any', 'every', 'grep', 'inject', 'sleep', 'print', 'printf', 'println',
        'get', 'set', 'run', 'memoize', 'memoizeAtMost', 'memoizeAtLeast',
        'memoizeBetween', 'trampoline', 'leftShift', 'rightShift', 'andThen',
        'compose', 'asWritable', 'getDirective', 'setDirective'
    ] as Set

    static String sha(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }

    // Fresh Groovy literals have known DGM dispatch. A List/Iterable cast of an
    // arbitrary object is insufficient: a subtype may implement its own each().
    static Map receiverKind(Expression e) {
        if (e instanceof ListExpression) return [kind: 'list', nullable: false]
        if (e instanceof MapExpression) return [kind: 'map', nullable: false]
        if (e instanceof CastExpression && e.type.array && e.expression instanceof ListExpression) {
            String element = e.type.componentType.name
            if (element in ['byte', 'short', 'int', 'long', 'float', 'double', 'char', 'boolean',
                            'Object', 'java.lang.Object', 'String', 'java.lang.String']) {
                return [kind: 'array', nullable: false]
            }
        }
        if (e instanceof ConstantExpression && e.value == null) return [kind: 'null', nullable: true]
        if (e instanceof TernaryExpression && !(e instanceof ElvisOperatorExpression)) {
            Map a = receiverKind(e.trueExpression), b = receiverKind(e.falseExpression)
            if (a && b && (a.kind == b.kind || a.kind == 'null' || b.kind == 'null')) {
                return [kind: a.kind == 'null' ? b.kind : a.kind, nullable: a.nullable || b.nullable]
            }
        }
        return null
    }

    static String knownType(ClassNode type, SourceUnit su) {
        String name = type.name
        if (name.contains('.')) return name
        // Explicit aliases/custom classes/star imports can redirect short names.
        def declared = su.AST.classes.find { it.nameWithoutPackage == name }
        if (declared && !declared.script) return null
        def explicit = su.AST.imports.find { it.alias == name }
        if (explicit) return explicit.type.name
        if (su.AST.starImports.any { it.packageName != 'java.util.' && it.packageName != 'java.lang.' }) return null
        if (name in ['ArrayList', 'LinkedHashMap', 'List', 'Map', 'Collection']) return 'java.util.' + name
        if (name in ['Object', 'Iterable']) return 'java.lang.' + name
        return null
    }

    static Map producerKind(Expression expression, SourceUnit su) {
        Map shape = receiverKind(expression)
        if (shape) return shape + [producer: 'literal']
        if (expression instanceof ConstructorCallExpression && !expression.usingAnonymousInnerClass) {
            String name = knownType(expression.type, su)
            List args = expression.arguments instanceof TupleExpression ? expression.arguments.expressions : []
            if (name in ['java.util.ArrayList', 'java.util.LinkedHashMap'] && args.size() <= 1) {
                return [kind: name.endsWith('ArrayList') ? 'list' : 'map', nullable: false,
                        producer: name]
            }
        }
        return null
    }

    static boolean compatibleLocal(VariableExpression variable, Map shape, SourceUnit su) {
        if (variable.dynamicTyped) return true
        String type = knownType(variable.type, su)
        if (type == 'java.lang.Object') return true
        if (shape.kind == 'list') return type in ['java.util.List', 'java.util.Collection', 'java.lang.Iterable', 'java.util.ArrayList']
        if (shape.kind == 'map') return type in ['java.util.Map', 'java.util.LinkedHashMap']
        return shape.kind == 'array' && variable.type.array
    }

    static Map localInventory(SourceUnit su) {
        Map declarations = new IdentityHashMap()
        Set writes = Collections.newSetFromMap(new IdentityHashMap())
        def binding = { VariableExpression v -> v.accessedVariable ?: v }
        def markWrite
        markWrite = { Expression e ->
            if (e instanceof VariableExpression) writes.add(binding(e))
            else if (e instanceof TupleExpression) e.expressions.each { markWrite(it) }
        }
        def visitor = new ClassCodeVisitorSupport() {
            MethodNode method
            List blocks = []
            int depth = 0
            protected SourceUnit getSourceUnit() { su }
            protected void visitConstructorOrMethod(MethodNode node, boolean constructor) {
                MethodNode prior = method
                method = node
                super.visitConstructorOrMethod(node, constructor)
                method = prior
            }
            void visitBlockStatement(BlockStatement block) {
                blocks << block
                super.visitBlockStatement(block)
                blocks.remove(blocks.size() - 1)
            }
            void visitClosureExpression(ClosureExpression e) {
                depth++; super.visitClosureExpression(e); depth--
            }
            void visitExpressionStatement(ExpressionStatement statement) {
                Expression e = statement.expression
                if (depth == 0 && method && e instanceof DeclarationExpression &&
                        !e.multipleAssignmentDeclaration && e.leftExpression instanceof VariableExpression &&
                        blocks && blocks[-1].statements.any { it.is(statement) }) {
                    VariableExpression v = e.leftExpression
                    Map shape = producerKind(e.rightExpression, su)
                    if (shape && (shape.kind == 'null' || !compatibleLocal(v, shape, su))) shape = null
                    declarations.put(binding(v), [shape: shape, block: blocks[-1], blocks: new ArrayList(blocks),
                        method: method, declaration: e, initializer: e.rightExpression,
                        variable: v, name: v.name, line: v.lineNumber])
                }
                super.visitExpressionStatement(statement)
            }
            void visitBinaryExpression(BinaryExpression e) {
                if (!(e instanceof DeclarationExpression) && e.operation.text in
                        ['=', '+=', '-=', '*=', '/=', '%=', '**=', '<<=', '>>=', '>>>=', '&=', '|=', '^=']) {
                    markWrite(e.leftExpression)
                }
                super.visitBinaryExpression(e)
            }
            void visitPrefixExpression(PrefixExpression e) {
                if (e.operation.text in ['++', '--']) markWrite(e.expression)
                super.visitPrefixExpression(e)
            }
            void visitPostfixExpression(PostfixExpression e) {
                markWrite(e.expression)
                super.visitPostfixExpression(e)
            }
        }
        su.AST.classes.each { visitor.visitClass(it) }
        Map inventory = [declarations: declarations, writes: writes]
        // DGM collect/list-findAll on a proven ArrayList yields a fresh ArrayList.
        boolean changed = true
        int iterations = 0
        while (changed && iterations++ <= declarations.size()) {
            changed = false
            declarations.each { variable, rec ->
                if (!rec.shape && !writes.contains(variable) && rec.initializer instanceof MethodCallExpression) {
                    MethodCallExpression call = rec.initializer
                    List args = call.arguments instanceof TupleExpression ? call.arguments.expressions : []
                    Map input = provenReceiver(call.objectExpression, su, inventory, rec.blocks, rec.method)
                    if (call.methodAsString in ['collect', 'findAll'] && !call.implicitThis && !call.spreadSafe &&
                            args.size() == 1 && args[0] instanceof ClosureExpression && input?.kind == 'list' &&
                            (!input.nullable || call.safe)) {
                        Map output = [kind: 'list', nullable: input.nullable && call.safe,
                            producer: 'DGM:' + call.methodAsString + '(' + input.producer + ')']
                        if (compatibleLocal(rec.variable, output, su)) {
                            rec.shape = output
                            changed = true
                        }
                    }
                }
            }
        }
        return inventory
    }

    static Map provenReceiver(Expression expression, SourceUnit su, Map inventory, List blocks, MethodNode method) {
        Map direct = producerKind(expression, su)
        if (direct) return direct
        if (expression instanceof VariableExpression) {
            def variable = expression.accessedVariable ?: expression
            Map declaration = inventory.declarations.get(variable)
            if (declaration?.shape && !inventory.writes.contains(variable) && declaration.method.is(method) &&
                    blocks.any { it.is(declaration.block) } &&
                    (declaration.declaration.lineNumber < expression.lineNumber ||
                     (declaration.declaration.lineNumber == expression.lineNumber &&
                      declaration.declaration.columnNumber < expression.columnNumber))) {
                return declaration.shape + [producer: 'local:' + declaration.shape.producer,
                    bindingName: declaration.name, declarationLine: declaration.line]
            }
        }
        return null
    }

    static int offset(String source, int line, int column) {
        if (line < 1 || column < 1) throw new IllegalStateException('Missing source position')
        int start = 0
        for (int i = 1; i < line; i++) {
            start = source.indexOf('\n', start) + 1
            if (start == 0) throw new IllegalStateException('Source position exceeds input')
        }
        return start + column - 1
    }

    static List span(String source, ASTNode node) {
        [offset(source, node.lineNumber, node.columnNumber),
         offset(source, node.lastLineNumber, node.lastColumnNumber)]
    }

    static String slice(String source, ASTNode node) {
        List range = span(source, node)
        source.substring(range[0] as int, range[1] as int)
    }

    // Only the parameter header is scanned, after AST recognition. Comments are
    // skipped so an arrow in a comment cannot become the body delimiter.
    static int skipTrivia(String text, int position) {
        int p = position
        while (p < text.length()) {
            if (Character.isWhitespace(text.charAt(p))) { p++; continue }
            if (text.startsWith('//', p)) {
                int end = text.indexOf('\n', p + 2)
                p = end < 0 ? text.length() : end + 1
                continue
            }
            if (text.startsWith('/*', p)) {
                int end = text.indexOf('*/', p + 2)
                if (end < 0) throw new IllegalStateException('Unterminated parameter comment')
                p = end + 2
                continue
            }
            break
        }
        return p
    }

    static String closureBody(String source, ClosureExpression closure) {
        List range = span(source, closure)
        int start = range[0], end = range[1]
        if (source.charAt(start) != '{' || source.charAt(end - 1) != '}') {
            throw new IllegalStateException("Unreliable closure span at ${closure.lineNumber}")
        }
        int body = start + 1
        if (closure.parameterSpecified) {
            Parameter param = closure.parameters[0]
            int p = offset(source, param.lineNumber, param.columnNumber)
            // Eligibility permits an untyped, unannotated single parameter only.
            p = skipTrivia(source, p)
            if (!source.startsWith(param.name, p)) {
                throw new IllegalStateException('Unexpected parameter source spelling')
            }
            p = skipTrivia(source, p + param.name.length())
            if (!source.startsWith('->', p)) throw new IllegalStateException('Missing parameter arrow')
            body = p + 2
        }
        return source.substring(body, end - 1)
    }

    static Map transform(String source, String name) {
        // Hide #include without changing positions. Libraries are transformed
        // independently, then the existing builder inlines them for all gates.
        String parsed = source.replaceAll('(?m)^[ \\t]*#include[^\\r\\n]*', { String line -> ' ' * line.length() })
        SourceUnit su = SourceUnit.create(name, parsed)
        su.parse(); su.completePhase(); su.nextPhase(); su.convert()
        // Resolve lexical identities without executing semantic transforms or
        // depending on name matching. Reassignments inside closures count too.
        su.AST.classes.each { new VariableScopeVisitor(su).visitClass(it) }
        Map inventory = localInventory(su)
        List edits = [], rejected = []
        Set seen = Collections.newSetFromMap(new IdentityHashMap())
        Set magicSites = [] as Set
        def dispatchVisitor = new ClassCodeVisitorSupport() {
            protected SourceUnit getSourceUnit() { su }
            void visitPropertyExpression(PropertyExpression e) {
                if (e.propertyAsString == 'metaClass') magicSites << e.lineNumber
                super.visitPropertyExpression(e)
            }
            void visitMethodCallExpression(MethodCallExpression e) {
                if (e.methodAsString in ['use', 'mixin', 'setMetaClass']) magicSites << e.lineNumber
                super.visitMethodCallExpression(e)
            }
        }
        su.AST.classes.each { dispatchVisitor.visitClass(it) }
        int nextId = 0
        String prefix = '__bat529EachReceiver'
        if (source.contains(prefix)) throw new IllegalStateException('Temporary-variable prefix already present')

        def visitor = new ClassCodeVisitorSupport() {
            MethodNode activeMethod
            int closureDepth = 0
            List blocks = []
            protected SourceUnit getSourceUnit() { su }

            void visitBlockStatement(BlockStatement block) {
                blocks << block
                super.visitBlockStatement(block)
                blocks.remove(blocks.size() - 1)
            }

            protected void visitConstructorOrMethod(MethodNode node, boolean constructor) {
                MethodNode old = activeMethod
                activeMethod = node
                super.visitConstructorOrMethod(node, constructor)
                activeMethod = old
            }

            void visitClosureExpression(ClosureExpression e) {
                closureDepth++
                super.visitClosureExpression(e)
                closureDepth--
            }

            void visitExpressionStatement(ExpressionStatement statement) {
                Expression expression = statement.expression
                DeclarationExpression declaration = expression instanceof DeclarationExpression &&
                    !expression.multipleAssignmentDeclaration &&
                    expression.leftExpression instanceof VariableExpression ? expression : null
                Expression candidate = declaration ? declaration.rightExpression : expression
                if (candidate instanceof MethodCallExpression && candidate.methodAsString in ['each', 'collect', 'findAll']) {
                    MethodCallExpression call = candidate
                    String operation = call.methodAsString
                    seen.add(call)
                    String reason = null
                    List arguments = call.arguments instanceof TupleExpression ? call.arguments.expressions : []
                    ClosureExpression closure = arguments.size() == 1 && arguments[0] instanceof ClosureExpression ? arguments[0] : null
                    Map shape = provenReceiver(call.objectExpression, su, inventory, blocks, activeMethod)
                    if (!activeMethod || (activeMethod.name == 'run' && activeMethod.declaringClass.script)) reason = 'root DSL/script statement'
                    else if (closureDepth != 0) reason = 'inside another closure'
                    else if (magicSites) reason = 'file changes dynamic dispatch/metaclass'
                    else if (call.implicitThis || call.spreadSafe) reason = 'implicit or spread dispatch'
                    else if (!closure) reason = 'not one literal closure argument'
                    else if (!shape || shape.kind == 'null') reason = 'receiver dispatch/type not proven'
                    else if (shape.nullable && !call.safe) reason = 'nullable non-safe receiver'
                    else if (closure.parameters == null) reason = 'explicit zero-parameter closure'
                    else if (closure.parameterSpecified && (closure.parameters.length != 1 ||
                             !closure.parameters[0].dynamicTyped || closure.parameters[0].annotations ||
                             closure.parameters[0].hasInitialExpression())) reason = 'parameter arity/type/annotation/default'
                    else if (declaration && (operation == 'each' || !blocks ||
                             !blocks[-1].statements.any { it.is(statement) })) reason = 'unsupported declaration context'
                    else if (operation != 'each' && shape.kind != 'list') reason = 'collect/findAll output type not supported'
                    else if (operation != 'each' && (!(closure.code instanceof BlockStatement) ||
                             closure.code.statements.size() != 1 ||
                             !(closure.code.statements[0] instanceof ExpressionStatement) ||
                             closure.code.statements[0].expression instanceof DeclarationExpression)) reason = 'collect/findAll requires one expression'
                    else if (declaration && !compatibleLocal(declaration.leftExpression,
                             [kind: 'list', nullable: false], su)) reason = 'result declaration type not supported'

                    if (!reason) {
                        Set hazards = [] as Set
                        def bodyVisitor = new CodeVisitorSupport() {
                            void visitClosureExpression(ClosureExpression e) { hazards << 'nested closure' }
                            void visitReturnStatement(ReturnStatement s) { hazards << 'closure return' }
                            void visitBreakStatement(BreakStatement s) { hazards << 'break boundary' }
                            void visitContinueStatement(ContinueStatement s) { hazards << 'continue boundary' }
                            void visitVariableExpression(VariableExpression e) {
                                if (MAGIC.contains(e.name)) hazards << 'closure identity or dispatch dependency'
                                if (declaration && (e.accessedVariable ?: e).is(
                                        declaration.leftExpression.accessedVariable ?: declaration.leftExpression)) {
                                    hazards << 'initializer references its result binding'
                                }
                                super.visitVariableExpression(e)
                            }
                            void visitPropertyExpression(PropertyExpression e) {
                                if (MAGIC.contains(e.propertyAsString)) hazards << 'closure identity or dispatch dependency'
                                super.visitPropertyExpression(e)
                            }
                            void visitMethodCallExpression(MethodCallExpression e) {
                                if (e.implicitThis && MAGIC.contains(e.methodAsString)) hazards << 'closure identity or dispatch dependency'
                                super.visitMethodCallExpression(e)
                            }
                        }
                        closure.code.visit(bodyVisitor)
                        call.objectExpression.visit(bodyVisitor)
                        if (hazards) reason = hazards.sort().join(', ')
                    }
                    if (reason) {
                        rejected << [line: call.lineNumber, method: activeMethod?.name,
                            operation: operation, producer: shape?.producer, reason: reason]
                    } else {
                        String receiver = slice(source, call.objectExpression)
                        String parameter = closure.parameterSpecified ? closure.parameters[0].name : 'it'
                        String body = closureBody(source, closure)
                        String temporary = prefix + (++nextId)
                        String values = shape.kind == 'map' ? temporary + '.entrySet()' : temporary
                        // An else branch prevents a surrounding unbraced if's else
                        // from attaching to the introduced if (dangling-else rule).
                        String output = temporary + 'Result'
                        String loopBody = body
                        if (operation != 'each') {
                            String value = slice(source, closure.code.statements[0].expression)
                            loopBody = operation == 'collect' ? output + '.add((' + value + '))' :
                                'if (' + value + ') ' + output + '.add(' + parameter + ')'
                        }
                        String loop = 'for (def ' + parameter + ' in ' + values + ') {' + loopBody + '\n}'
                        String setup = 'def ' + temporary + ' = (' + receiver + ')\n'
                        if (operation == 'each') {
                            if (call.safe) loop = 'if (' + temporary + ' != null) {\n' + loop + '\n}'
                        } else if (call.safe) {
                            setup += 'def ' + output + ' = null\n'
                            loop = 'if (' + temporary + ' != null) {\n' + output + ' = []\n' + loop + '\n}'
                        } else {
                            setup += 'def ' + output + ' = []\n'
                        }
                        String yielded = operation == 'each' ? temporary : output
                        String replacement
                        ASTNode target = declaration ?: call
                        if (declaration) {
                            List declarationRange = span(source, declaration)
                            List callRange = span(source, call)
                            String header = source.substring(declarationRange[0] as int, callRange[0] as int)
                            replacement = setup + loop + '\n' + header + yielded
                        } else {
                            replacement = 'if (true) {\n' + setup + loop + '\n' + yielded + '\n} else { null }'
                        }
                        List range = span(source, target)
                        edits << [start: range[0], end: range[1], replacement: replacement,
                                  line: call.lineNumber, method: activeMethod.name, receiverKind: shape.kind,
                                  producer: shape.producer, bindingName: shape.bindingName,
                                  declarationLine: shape.declarationLine, operation: operation,
                                  safe: call.safe, original: slice(source, target)]
                    }
                }
                super.visitExpressionStatement(statement)
            }

            void visitMethodCallExpression(MethodCallExpression e) {
                if (e.methodAsString in ['each', 'collect', 'findAll'] && !seen.contains(e)) {
                    rejected << [line: e.lineNumber, method: activeMethod?.name,
                        operation: e.methodAsString, reason: 'value/return/chained context']
                }
                super.visitMethodCallExpression(e)
            }
        }
        su.AST.classes.each { visitor.visitClass(it) }
        edits.sort { a, b -> a.start <=> b.start }
        for (int i = 1; i < edits.size(); i++) {
            if (edits[i].start < edits[i - 1].end) throw new IllegalStateException('Overlapping source edits')
        }
        String rewritten = source
        edits.reverseEach { e -> rewritten = rewritten.substring(0, e.start as int) + e.replacement + rewritten.substring(e.end as int) }
        List producers = inventory.declarations.findAll { variable, rec -> rec.shape }.collect { variable, rec ->
            [method: rec.method.name, name: rec.name, line: rec.line, kind: rec.shape.kind,
             producer: rec.shape.producer, reassigned: inventory.writes.contains(variable)]
        }.sort { a, b -> a.line <=> b.line }
        return [source: rewritten, edits: edits, rejected: rejected, producers: producers]
    }
}

// GroovyClassLoader.parseClass exposes EachLoopConverter to the parity fixture
// without running this command-line section.
if (System.getenv('GITHUB_ACTIONS') != 'true') {
    throw new IllegalStateException('This converter may execute only in GitHub Actions')
}
if (GroovySystem.version != '2.4.21') throw new IllegalStateException('Expected pinned Groovy 2.4.21')
boolean inventoryOnly = args.length == 3 && args[0] == '--inventory'
if (!(args.length == 2 || inventoryOnly)) throw new IllegalArgumentException('usage: convert.groovy [--inventory] <repo-root> <output-dir>')
File root = new File(args[inventoryOnly ? 1 : 0]).canonicalFile
File output = new File(args[inventoryOnly ? 2 : 1]).canonicalFile
if (output == root || root.path.startsWith(output.path + File.separator) ||
        output.path.startsWith(new File(root, 'libraries').path + File.separator)) {
    throw new IllegalArgumentException('Output must be a distinct artifact directory')
}
List inputs = [new File(root, 'hubitat-mcp-server.groovy')]
inputs.addAll(new File(root, 'libraries').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name })
List manifest = []
for (File input : inputs) {
    byte[] bytes = input.bytes
    String relative = root.toPath().relativize(input.toPath()).toString().replace('\\', '/')
    Map result = EachLoopConverter.transform(new String(bytes, 'UTF-8'), relative)
    byte[] rewritten = result.source.getBytes('UTF-8')
    if (!inventoryOnly) {
        File dest = new File(output, 'rewritten/' + relative)
        dest.parentFile.mkdirs()
        dest.bytes = rewritten
    }
    manifest << [path: relative, originalSha256: EachLoopConverter.sha(bytes),
                 rewrittenSha256: EachLoopConverter.sha(rewritten), removedClosures: result.edits.size(),
                 edits: result.edits, rejected: result.rejected, producers: result.producers]
}
output.mkdirs()
new File(output, 'manifest.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
    formatVersion: 1, groovyVersion: GroovySystem.version, inventoryOnly: inventoryOnly,
    revision: System.getenv('GITHUB_SHA'), removedClosures: manifest.sum { it.removedClosures },
    operations: manifest.collectMany { it.edits }.countBy { it.operation },
    provenance: manifest.collectMany { it.edits }.countBy { it.producer },
    files: manifest
])) + '\n'
println "Prepared ${manifest.sum { it.removedClosures }} collection-to-loop replacements across ${manifest.size()} exact source files"
