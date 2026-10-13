// Remote-only callback extraction. It changes callback source, not collection dispatch.
import groovy.json.JsonOutput
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.*
import org.codehaus.groovy.classgen.VariableScopeVisitor
import org.codehaus.groovy.antlr.parser.GroovyLexer
import org.codehaus.groovy.antlr.parser.GroovyTokenTypes
import java.lang.reflect.Modifier
import java.security.MessageDigest

class BroadCallbackExtractor {
    static final Set OPERATIONS = [
        'each', 'eachWithIndex', 'reverseEach', 'collect', 'collectEntries', 'collectMany',
        'find', 'findAll', 'findResult', 'findResults', 'any', 'every', 'count', 'countBy',
        'groupBy', 'groupEntriesBy', 'split', 'inject', 'sort', 'toSorted', 'unique',
        'toUnique', 'min', 'max', 'sum', 'grep', 'takeWhile', 'dropWhile',
        'times', 'upto', 'downto', 'step', 'eachMatch', 'replaceAll', 'replaceFirst'
    ] as Set
    static final Set IDENTITY_PROPERTIES = [
        'owner', 'delegate', 'thisObject', 'resolveStrategy', 'maximumNumberOfParameters',
        'parameterTypes', 'directive', 'metaClass'
    ] as Set
    static final Set SELF_METHODS = [
        'getOwner', 'getDelegate', 'setDelegate', 'getThisObject', 'getResolveStrategy',
        'setResolveStrategy', 'getMaximumNumberOfParameters', 'getParameterTypes',
        'getDirective', 'setDirective', 'getMetaClass', 'setMetaClass', 'getClass',
        'getProperty', 'setProperty', 'invokeMethod', 'call', 'doCall', 'run',
        'rehydrate', 'dehydrate', 'curry', 'rcurry', 'ncurry', 'clone', 'hashCode',
        'equals', 'is', 'toString', 'inspect', 'dump', 'asType', 'asBoolean',
        'memoize', 'trampoline', 'asWritable', 'with', 'tap', 'use', 'mixin'
    ] as Set
    static final Set DSL = [
        'definition', 'library', 'preferences', 'mappings', 'path', 'action', 'page',
        'dynamicPage', 'section', 'input', 'paragraph', 'href', 'label'
    ] as Set
    static final Set SCRIPT_GLOBALS = [
        'this', 'state', 'atomicState', 'settings', 'app', 'location', 'log', 'params',
        'request', 'interfaces'
    ] as Set

    static String sha(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }
    static Set identities() { Collections.newSetFromMap(new IdentityHashMap()) }
    static def binding(VariableExpression v) { v.accessedVariable ?: v }
    static boolean lexicalVariable(variable) {
        variable instanceof Parameter || variable instanceof VariableExpression
    }
    static String parameterText(Parameter parameter) {
        (parameter.dynamicTyped ? 'def' : parameter.type.name) + ' ' + parameter.name
    }

    static List tokens(String source, Class spans) {
        def stream = new GroovyLexer(new StringReader(source)).plumb()
        List result = []
        while (true) {
            def token = stream.nextToken()
            if (token.type == GroovyTokenTypes.EOF) break
            result << [type: token.type, text: token.text,
                start: spans.offset(source, token.line, token.column)]
        }
        return result
    }

    static int bodyStart(String source, ClosureExpression closure, List range, List tokenList) {
        if (!closure.parameterSpecified) return (range[0] as int) + 1
        def arrow = tokenList.find { token ->
            token.start > range[0] && token.start < range[1] && token.text == '->' &&
                source.startsWith('->', token.start as int)
        }
        if (!arrow) throw new IllegalStateException('No lexical parameter arrow at ' + closure.lineNumber)
        return (arrow.start as int) + 2
    }

    static List callSpan(String source, MethodCallExpression call, Class spans, Map pairs) {
        List range = spans.span(source, call)
        int start = range[0], end = range[1]
        // Groovy 2.4 can position a GString value's call on the enclosing
        // interpolation braces. Those delimiters belong to the GString.
        if (start > 0 && source.charAt(start - 1) == '$' && source.charAt(start) == '{' &&
                pairs.containsKey(start)) {
            int interpolationEnd = pairs.get(start)
            if (end > interpolationEnd) throw new IllegalStateException('Call crosses GString boundary')
            start++
            if (end == interpolationEnd) end--
        }
        return [start, end]
    }

    // Replacements may nest. Include only the outermost child in each segment:
    // its generated helper already contains its own lowered descendants.
    static String render(String source, int start, int end, List replacements) {
        List relevant = replacements.findAll { it.start >= start && it.end <= end }
            .sort { a, b -> (a.start <=> b.start) ?: (b.end <=> a.end) }
        int cursor = start
        StringBuilder out = new StringBuilder()
        relevant.each { edit ->
            if (edit.start >= cursor) {
                out.append(source.substring(cursor, edit.start as int))
                out.append(edit.replacement)
                cursor = edit.end
            } else if (edit.end > cursor) {
                throw new IllegalStateException('Crossing source replacements at ' + edit.start)
            }
        }
        out.append(source.substring(cursor, end))
        return out.toString()
    }

    static Map inspectBody(ClosureExpression closure, Set methodWrites, boolean foreignAncestor) {
        Set owned = identities(), references = identities(), hazards = [] as Set
        Set localNames = [] as Set
        def addParameters = { ClosureExpression e ->
            e.parameters?.each { owned.add(it); localNames.add(it.name) }
            def implicit = e.variableScope?.getDeclaredVariable('it')
            if (implicit) owned.add(implicit)
        }
        addParameters(closure)
        def declared = new CodeVisitorSupport() {
            void visitClosureExpression(ClosureExpression e) {
                addParameters(e)
                super.visitClosureExpression(e)
            }
            void visitDeclarationExpression(DeclarationExpression e) {
                if (e.leftExpression instanceof VariableExpression) {
                    owned.add(binding(e.leftExpression)); localNames.add(e.leftExpression.name)
                } else if (e.leftExpression instanceof TupleExpression) {
                    e.leftExpression.expressions.each { v ->
                        if (v instanceof VariableExpression) { owned.add(binding(v)); localNames.add(v.name) }
                    }
                }
                super.visitDeclarationExpression(e)
            }
            void visitForLoop(ForStatement e) {
                owned.add(e.variable); localNames.add(e.variable.name)
                super.visitForLoop(e)
            }
            void visitCatchStatement(CatchStatement e) {
                owned.add(e.variable); localNames.add(e.variable.name)
                super.visitCatchStatement(e)
            }
        }
        closure.code.visit(declared)
        // The scope also records a free local called as fix(x), whose method
        // name is a ConstantExpression rather than a VariableExpression.
        def addScopeReferences = { ClosureExpression e ->
            def iterator = e.variableScope?.referencedLocalVariablesIterator
            if (iterator) while (iterator.hasNext()) references.add(iterator.next())
        }
        addScopeReferences(closure)
        def reader = new CodeVisitorSupport() {
            void visitClosureExpression(ClosureExpression e) {
                addScopeReferences(e)
                super.visitClosureExpression(e)
            }
            void visitVariableExpression(VariableExpression e) {
                def variable = binding(e)
                if (!e.thisExpression && !e.superExpression && lexicalVariable(variable)) references.add(variable)
                if (e.superExpression) hazards << 'super binding'
                if (variable instanceof DynamicVariable && IDENTITY_PROPERTIES.contains(e.name)) {
                    hazards << 'closure identity/delegate dependency'
                }
                if (foreignAncestor && variable instanceof DynamicVariable &&
                        !SCRIPT_GLOBALS.contains(e.name) && !e.thisExpression) {
                    hazards << 'foreign callback dynamic property binding'
                }
                super.visitVariableExpression(e)
            }
            void visitMethodCallExpression(MethodCallExpression e) {
                if (e.implicitThis && SELF_METHODS.contains(e.methodAsString)) {
                    hazards << 'closure inherited method dependency'
                }
                if (e.implicitThis && DSL.contains(e.methodAsString)) hazards << 'Hubitat DSL binding'
                if (foreignAncestor && e.implicitThis) hazards << 'foreign callback implicit method binding'
                super.visitMethodCallExpression(e)
            }
            void visitConstructorCallExpression(ConstructorCallExpression e) {
                if (e.usingAnonymousInnerClass) hazards << 'anonymous class lexical binding'
                super.visitConstructorCallExpression(e)
            }
        }
        closure.code.visit(reader)
        List captures = references.findAll { lexicalVariable(it) && !owned.contains(it) }
            .sort { a, b -> a.name <=> b.name }
        captures.each { variable ->
            if (methodWrites.contains(variable)) hazards << 'mutable captured binding: ' + variable.name
            if (localNames.contains(variable.name)) hazards << 'capture/local name collision: ' + variable.name
        }
        if (captures*.name.unique().size() != captures.size()) hazards << 'ambiguous capture names'
        return [captures: captures, hazards: hazards.sort()]
    }

    static Map transform(String source, String name, Class spans) {
        if (source.contains('_bat529Cb_')) throw new IllegalStateException('Source already contains broad callback helpers')
        String parsed = source.replaceAll('(?m)^[ \\t]*#include[^\\r\\n]*', { String line -> ' ' * line.length() })
        SourceUnit su = SourceUnit.create(name, parsed)
        su.parse(); su.completePhase(); su.nextPhase(); su.convert()
        su.AST.classes.each { new VariableScopeVisitor(su).visitClass(it) }
        Map pairs = spans.curlySpans(parsed)
        List tokenList = tokens(parsed, spans), candidates = [], rejected = []
        Map closureCalls = new IdentityHashMap(), writes = new IdentityHashMap()
        Set dispatchHazards = [] as Set
        Map context = [method: null, ancestors: []]
        def markWrite
        markWrite = { Expression e ->
            if (!context.method) return
            if (e instanceof VariableExpression) {
                if (!writes.containsKey(context.method)) writes.put(context.method, identities())
                writes.get(context.method).add(binding(e))
            } else if (e instanceof TupleExpression) e.expressions.each { markWrite(it) }
        }
        def visitor = new ClassCodeVisitorSupport() {
            protected SourceUnit getSourceUnit() { su }
            protected void visitConstructorOrMethod(MethodNode node, boolean constructor) {
                def prior = context.method
                context.method = node
                super.visitConstructorOrMethod(node, constructor)
                context.method = prior
            }
            void visitPropertyExpression(PropertyExpression e) {
                if (e.propertyAsString == 'metaClass') dispatchHazards << e.lineNumber
                super.visitPropertyExpression(e)
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
            void visitMethodCallExpression(MethodCallExpression e) {
                if (e.methodAsString in ['setMetaClass', 'use', 'mixin']) dispatchHazards << e.lineNumber
                List args = e.arguments instanceof TupleExpression ? e.arguments.expressions : []
                args.findAll { it instanceof ClosureExpression }.each { closure ->
                    closureCalls.put(closure, [call: e, method: context.method, operation: e.methodAsString])
                }
                super.visitMethodCallExpression(e)
            }
            void visitClosureExpression(ClosureExpression e) {
                def call = closureCalls.get(e)
                if (call && OPERATIONS.contains(call.operation)) {
                    candidates << [closure: e, call: call.call, method: context.method,
                        operation: call.operation, ancestors: new ArrayList(context.ancestors)]
                }
                context.ancestors << e
                super.visitClosureExpression(e)
                context.ancestors.remove(context.ancestors.size() - 1)
            }
        }
        su.AST.classes.each { visitor.visitClass(it) }
        List eligible = []
        candidates.each { candidate ->
            ClosureExpression closure = candidate.closure
            MethodNode method = candidate.method
            String reason = null
            if (!method || method.name == 'run' || !method.declaringClass.script) reason = 'root DSL or non-script class'
            else if (Modifier.isStatic(method.modifiers)) reason = 'static method owner binding'
            else if (method.annotations.any { it.classNode.name.endsWith('CompileStatic') }) reason = 'retain existing static callback compilation'
            else if (dispatchHazards) reason = 'file changes metaclass/category dispatch'
            else if (candidate.call.implicitThis || candidate.call.spreadSafe ||
                     (candidate.call.objectExpression instanceof VariableExpression &&
                      candidate.call.objectExpression.superExpression)) reason = 'implicit/spread/super callback dispatch'
            else if (closure.parameters == null) reason = 'explicit zero-parameter closure'
            else if (closure.parameters.any { it.annotations || it.hasInitialExpression() || it.type.array }) reason = 'annotated/default/array-varargs parameter'
            boolean dslAncestor = candidate.ancestors.any { ancestor ->
                def parent = closureCalls.get(ancestor)
                parent && DSL.contains(parent.operation)
            }
            boolean foreignAncestor = candidate.ancestors.any { ancestor ->
                def parent = closureCalls.get(ancestor)
                !parent || !OPERATIONS.contains(parent.operation)
            }
            if (!reason && dslAncestor) reason = 'inside Hubitat DSL'
            Map body = reason ? null : inspectBody(closure, writes.get(method) ?: identities(), foreignAncestor)
            if (!reason && body.hazards) reason = body.hazards.join('; ')
            if (reason) {
                rejected << [line: closure.lineNumber, method: method?.name,
                    operation: candidate.operation, reason: reason]
            } else {
                List range = spans.closureSpan(source, closure, candidate.call, pairs)
                candidate.range = range
                candidate.bodyStart = bodyStart(source, closure, range, tokenList)
                candidate.captures = body.captures
                eligible << candidate
            }
        }
        // Rebuild complete calls using ordinary parenthesized arguments. Merely
        // replacing the trailing block would turn inject(0) { ... } into
        // inject(0)(callback), accidentally invoking the operation's result.
        Map grouped = new IdentityHashMap()
        eligible.each { candidate ->
            if (!grouped.containsKey(candidate.call)) grouped.put(candidate.call, [])
            grouped.get(candidate.call) << candidate
        }
        List groups = grouped.collect { call, callbacks ->
            [call: call, callbacks: callbacks, range: callSpan(source, call, spans, pairs)]
        }.sort { a, b -> (a.range[1] - a.range[0]) <=> (b.range[1] - b.range[0]) }
        List edits = [], callbackInventory = [], helpers = [], helperInventory = []
        String tag = sha(name.getBytes('UTF-8')).substring(0, 8)
        int sequence = 0
        groups.each { group ->
          Map callbackReplacements = new IdentityHashMap()
          group.callbacks.each { candidate ->
            ClosureExpression closure = candidate.closure
            String helper = '_bat529Cb_' + tag + '_' + (++sequence)
            List callbackParameters = closure.parameterSpecified ? closure.parameters.collect { parameterText(it) } : ['def it']
            List captureParameters = candidate.captures.collect { 'def ' + it.name }
            String body = render(source, candidate.bodyStart as int, (candidate.range[1] as int) - 1, edits)
            String definition = 'def ' + helper + '(' + (captureParameters + callbackParameters).join(', ') +
                ') {' + body + '\n}\n'
            String replacement = '(this.&' + helper + ')'
            if (candidate.captures) replacement = '(' + replacement + '.curry([' +
                candidate.captures.collect { it.name }.join(', ') + '] as Object[]))'
            callbackReplacements.put(closure, replacement)
            callbackInventory << [start: candidate.range[0], end: candidate.range[1],
                original: source.substring(candidate.range[0] as int, candidate.range[1] as int),
                replacement: replacement, helper: helper, method: candidate.method.name,
                line: closure.lineNumber, operation: candidate.operation,
                captures: candidate.captures.collect { it.name }]
            helpers << definition
            helperInventory << [name: helper, operation: candidate.operation,
                callbackArity: callbackParameters.size(), captures: candidate.captures.collect { it.name },
                bodySourceCharacters: body.length()]
          }
          MethodCallExpression call = group.call
          List receiverRange = spans.span(source, call.objectExpression)
          String receiver = render(source, receiverRange[0] as int, receiverRange[1] as int, edits)
          List arguments = call.arguments instanceof TupleExpression ? call.arguments.expressions : []
          List renderedArguments = arguments.collect { argument ->
              if (callbackReplacements.containsKey(argument)) return callbackReplacements.get(argument)
              List range = argument instanceof ClosureExpression ?
                  spans.closureSpan(source, argument, call, pairs) : spans.span(source, argument)
              return render(source, range[0] as int, range[1] as int, edits)
          }
          String replacement = '(' + receiver + ')' + (call.safe ? '?.' : '.') +
              call.methodAsString + '(' + renderedArguments.join(', ') + ')'
          edits << [start: group.range[0], end: group.range[1],
              original: source.substring(group.range[0] as int, group.range[1] as int),
              replacement: replacement, operation: call.methodAsString,
              callbacks: group.callbacks.size()]
        }
        String rewritten = render(source, 0, source.length(), edits)
        if (helpers) rewritten += '\n\n' + helpers.join('\n')
        return [source: rewritten, removedClosures: callbackInventory.size(), callbacks: callbackInventory, edits: edits,
            helpers: helperInventory, appendedSource: helpers ? '\n\n' + helpers.join('\n') : '',
            rejected: rejected, operations: callbackInventory.countBy { it.operation },
            candidateCallbacks: candidates.size()]
    }
}

if (System.getenv('GITHUB_ACTIONS') != 'true') throw new IllegalStateException('GitHub Actions only')
if (GroovySystem.version != '2.4.21') throw new IllegalStateException('Expected stock Groovy 2.4.21')
if (args.length != 2) throw new IllegalArgumentException('usage: broad.groovy <repo-root> <output-directory>')
File root = new File(args[0]).canonicalFile, output = new File(args[1]).canonicalFile
if (root == output || root.path.startsWith(output.path + File.separator)) throw new IllegalArgumentException('Unsafe output directory')
GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass(new File(root, 'experiments/closure-loops/convert.groovy'))
Class spans = loader.loadClass('EachLoopConverter')
List inputs = [new File(root, 'hubitat-mcp-server.groovy')]
inputs.addAll(new File(root, 'libraries').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name })
List files = []
for (File input : inputs) {
    byte[] before = input.bytes
    String relative = root.toPath().relativize(input.toPath()).toString().replace('\\', '/')
    Map result = BroadCallbackExtractor.transform(new String(before, 'UTF-8'), relative, spans)
    byte[] after = result.source.getBytes('UTF-8')
    File destination = new File(output, 'rewritten/' + relative)
    destination.parentFile.mkdirs()
    destination.bytes = after
    files << [path: relative, originalSha256: BroadCallbackExtractor.sha(before),
        rewrittenSha256: BroadCallbackExtractor.sha(after), removedClosures: result.removedClosures,
        candidateCallbacks: result.candidateCallbacks, callbacks: result.callbacks, edits: result.edits,
        helpers: result.helpers, appendedSource: result.appendedSource,
        operations: result.operations, rejected: result.rejected]
}
output.mkdirs()
new File(output, 'manifest.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
    formatVersion: 2, generator: 'named-method-callbacks', groovyVersion: GroovySystem.version,
    revision: System.getenv('GITHUB_SHA'), removedClosures: files.sum { it.removedClosures },
    candidateCallbacks: files.sum { it.candidateCallbacks },
    operations: files.collectMany { it.callbacks }.countBy { it.operation },
    exclusions: files.collectMany { it.rejected }.countBy { it.reason },
    largestHelperBodyCharacters: files.collectMany { it.helpers }.collect { it.bodySourceCharacters }.max() ?: 0,
    files: files
])) + '\n'
println 'Prepared broad callback extraction: ' + files.sum { it.removedClosures } + ' removed closure literals'
