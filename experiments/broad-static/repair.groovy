// Reuse the compiler/lexer/source-origin utilities; never execute product DSL.
assert System.getenv('GITHUB_ACTIONS') == 'true': 'Run repair only in GitHub Actions'
assert args.length == 2: 'repair.groovy <root> <output>'
File repairRoot = new File(args[0]).canonicalFile
String preparation = new File(repairRoot, 'experiments/broad-static/prepare.groovy').getText('UTF-8').replace('\r\n', '\n')
String prelude = preparation.substring(0, preparation.indexOf('\ndef typeName ='))
prelude = prelude.replace("assert !source.contains(prefix): 'Preparation has already been applied'\n", '')
String originMapping = preparation.substring(preparation.indexOf('\ndef originalEdit ='), preparation.indexOf('\nmethods.each { method ->\n    if (method.annotations.any'))
String measurement = preparation.substring(preparation.indexOf('\ndef metrics ='), preparation.indexOf('\ndef baselineUnit ='))
String classSizes = preparation.substring(preparation.indexOf('\nMap classFileSizes('))
String body = $/
Map plans = [:], helpers = [:]
def collectionKind
collectionKind = { Expression expression ->
    if (expression instanceof ListExpression) return 'java.util.List'
    if (expression instanceof MapExpression) return 'java.util.Map'
    if (expression instanceof BinaryExpression && expression.operation.text == '+') return collectionKind(expression.leftExpression)
    ClassNode type = expression instanceof CastExpression ? expression.type : expression instanceof VariableExpression ? expression.type : null
    if (!type && expression instanceof MethodCallExpression && expression.methodAsString && byName[expression.methodAsString]?.size() == 1) type = byName[expression.methodAsString][0].returnType
    String name = type?.name
    if (name in ['Map', 'java.util.Map', 'LinkedHashMap', 'java.util.LinkedHashMap']) return 'java.util.Map'
    if (name in ['Set', 'java.util.Set', 'HashSet', 'java.util.HashSet', 'LinkedHashSet', 'java.util.LinkedHashSet']) return 'java.util.Set'
    if (name in ['List', 'java.util.List', 'ArrayList', 'java.util.ArrayList', 'Collection', 'java.util.Collection']) return 'java.util.List'
    null
}
methods.findAll { method -> method.annotations.any { it.classNode.name == 'groovy.transform.CompileStatic' } }.each { method ->
    List candidates = []
    method.code.visit(new ClassCodeVisitorSupport() {
        protected SourceUnit getSourceUnit() { su }
        void visitBinaryExpression(BinaryExpression expression) {
            if (expression.operation.text == 'in' || expression.operation.text == '+' && collectionKind(expression.leftExpression)) candidates << expression
            super.visitBinaryExpression(expression)
        }
    })
    List edits = [], sites = []
    Map localHelpers = [:]
    candidates.sort { expression -> List r = span(expression); r ? r[1] - r[0] : Integer.MAX_VALUE }.each { expression ->
        List r = span(expression), left = span(expression.leftExpression), right = span(expression.rightExpression)
        assert r && left && right: 'Missing complete repair span for ' + method.name
        def fragment = { List part ->
            String text = source.substring(part[0], part[1])
            edits.findAll { it.start >= part[0] && it.end <= part[1] }.sort { a, b -> b.start <=> a.start ?: b.end <=> a.end }.each { edit ->
                text = text.substring(0, edit.start - part[0]) + edit.replacement + text.substring(edit.end - part[0])
            }
            text
        }
        String type = expression.operation.text == 'in' ? 'boolean' : collectionKind(expression.leftExpression)
        String name = '__bat529Repair_' + (expression.operation.text == 'in' ? 'In' : 'Plus_' + type.replace('.', '_'))
        String result = expression.operation.text == 'in' ? 'left in right' : '(' + type + ') (left + right)'
        localHelpers[name] = '@groovy.transform.CompileDynamic\nprivate ' + type + ' ' + name + '(Object left, Object right) {\n    return ' + result + '\n}\n'
        String replacement = name + '(' + fragment(left) + ', ' + fragment(right) + ')'
        edits.removeAll { it.start >= r[0] && it.end <= r[1] }
        assert !edits.any { it.start < r[1] && it.end > r[0] }: 'Crossing repair edits in ' + method.name
        edits << [start: r[0], end: r[1], replacement: replacement]
        sites << [line: expression.lineNumber, operation: expression.operation.text, type: type]
    }
    if (edits) plans[method.name] = [method: method.name, edits: edits, helpers: localHelpers, islands: [], storeBoundaries: [], explicitReturnType: null, explicitLocalTypes: [], repairedSites: sites]
}
assert plans: 'No affected static operators found'
List allEdits = plans.values().collectMany { it.edits }.sort { a, b -> a.start <=> b.start ?: a.end <=> b.end }
StringBuilder candidate = new StringBuilder()
int previous = 0
allEdits.each { edit ->
    assert edit.start >= previous: 'Overlapping repair edits'
    candidate.append(source.substring(previous, edit.start)).append(edit.replacement)
    previous = edit.end
}
candidate.append(source.substring(previous))
plans.values().each { helpers.putAll(it.helpers) }
helpers.keySet().sort().each { candidate.append('\n').append(helpers[it]) }
new File(output, 'candidate-inlined.groovy').text = candidate.toString()
def beforeUnit = unitFor(source), afterUnit = unitFor(candidate.toString())
beforeUnit.compile(Phases.CLASS_GENERATION)
afterUnit.compile(Phases.CLASS_GENERATION)
Map before = metrics(beforeUnit.classes), after = metrics(afterUnit.classes)
assert after.parentConstantPool < 64000
assert after.parentMethods < 65535
assert after.largestMethodBytes < 60000
Map files = [:]
resolved.origins.each { origin ->
    String relative = root.toPath().relativize(origin.original.file.toPath()).toString().replace('\\', '/')
    files[relative] = sha(origin.original.file.bytes)
}
List selected = plans.values().collect { plan ->
    plan + [edits: plan.edits.collect { originalEdit(it.start, it.end, it.replacement) }]
}
Map report = [mode: 'repair', revision: System.getenv('GITHUB_SHA'), groovy: GroovySystem.version, files: files,
              selected: selected, retainedStaticBodies: methods.count { method -> method.annotations.any { it.classNode.name == 'groovy.transform.CompileStatic' } },
              addedStaticBodies: 0, explicitMethodReturns: 0, explicitLocalVariables: 0, before: before, after: after,
              operations: selected.collectMany { it.repairedSites }.countBy { it.operation }, helperCount: helpers.size()]
new File(output, 'report.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n'
println JsonOutput.prettyPrint(JsonOutput.toJson(report.findAll { k, v -> k in ['mode', 'before', 'after', 'operations', 'helperCount'] }))
/$
new GroovyShell(new Binding([args: [repairRoot.absolutePath, new File(args[1]).absolutePath, 'groovy.lang.Script', 'groovy24'] as String[]])).evaluate(prelude + originMapping + measurement + body + classSizes)
