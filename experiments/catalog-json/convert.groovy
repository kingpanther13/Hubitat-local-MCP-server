// Remote-only source generation. Actual generated source is validated by the existing full CI.
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import java.security.MessageDigest

assert System.getenv('GITHUB_ACTIONS') == 'true': 'Catalog generation runs only inside GitHub Actions'
assert args.length == 2: 'usage: convert.groovy <repo-root> <absent-or-empty-output-dir>'
File root = new File(args[0]).canonicalFile
File output = new File(args[1]).canonicalFile
assert !output.exists() || (output.isDirectory() && !output.list()): 'Output must be absent or empty'
File app = new File(root, 'hubitat-mcp-server.groovy')
assert app.isFile()
int chunkBudget = 48000
String reader = '_bat529EmbeddedCatalogRead'
String decoder = '_bat529EmbeddedCatalogDecode'
String helperSource = '''
@groovy.transform.CompileStatic
private Object _bat529EmbeddedCatalogRead(List<String> chunks) {
    StringBuilder text = new StringBuilder()
    for (String chunk : chunks) text.append(chunk)
    JsonSlurper parser = new JsonSlurper()
    parser.setCheckDates(false)
    return _bat529EmbeddedCatalogDecode(parser.parseText(text.toString()))
}

@groovy.transform.CompileStatic
private Object _bat529EmbeddedCatalogDecode(Object value) {
    if (!(value instanceof List)) return value
    List packet = (List) value
    List contents = (List) packet.get(1)
    if (packet.get(0) == "m") {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        for (Object rawEntry : contents) {
            List entry = (List) rawEntry
            result.put((String) entry.get(0), _bat529EmbeddedCatalogDecode(entry.get(1)))
        }
        return result
    }
    List<Object> result = new ArrayList<Object>()
    for (Object item : contents) result.add(_bat529EmbeddedCatalogDecode(item))
    return result
}
'''

def hash = { String text -> MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString() }
def parse = { String name, String source ->
    CompilationUnit unit = new CompilationUnit(new CompilerConfiguration())
    unit.addSource(name, source)
    unit.compile(Phases.CONVERSION) // AST selection only; no class generation or app/library DSL execution.
    unit.AST.classes.find { it.script }.methods.findAll { it.lineNumber > 0 && !it.synthetic }
}
def offset = { String text, int line, int column ->
    assert line > 0 && column > 0: 'Missing AST source position'
    int index = 0
    for (int i = 1; i < line; i++) {
        int newline = text.indexOf('\n', index)
        assert newline >= 0: 'Invalid AST line'
        index = newline + 1
    }
    index + column - 1
}
def encode
encode = { expression ->
    if (expression instanceof ConstantExpression) {
        Object value = expression.value
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer) return value
        throw new IllegalArgumentException('Unsupported constant type: ' + value.getClass().name)
    }
    if (expression instanceof ListExpression) return ['l', expression.expressions.collect { encode(it) }]
    if (expression instanceof MapExpression) {
        List entries = []
        for (def entry : expression.mapEntryExpressions) {
            if (!(entry.keyExpression instanceof ConstantExpression) || !(entry.keyExpression.value instanceof String)) {
                throw new IllegalArgumentException('Map key is not a constant String')
            }
            entries.add([entry.keyExpression.value, encode(entry.valueExpression)])
        }
        return ['m', entries]
    }
    throw new IllegalArgumentException('Nonliteral AST expression: ' + expression.getClass().simpleName)
}
def chunks = { String text ->
    List<String> result = []
    int begin = 0
    int bytes = 0
    for (int i = 0; i < text.length(); i++) {
        int code = (int) text.charAt(i)
        int width = code == 0 ? 2 : code <= 127 ? 1 : code <= 2047 ? 2 : 3
        if (bytes + width > chunkBudget) {
            result.add(text.substring(begin, i)); begin = i; bytes = 0
        }
        bytes += width
    }
    result.add(text.substring(begin))
    result
}
def quote = { String text -> "'" + text.replace('\\', '\\\\').replace("'", "\\'").replace('\r', '\\r').replace('\n', '\\n') + "'" }

Map originals = [:]
Map edits = [:]
List selected = []
List skipped = []
List<File> files = [app] + new File(root, 'libraries').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name }
for (File file : files) {
    String path = root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
    String source = file.getText('UTF-8')
    assert !source.contains(reader) && !source.contains(decoder): 'Catalog helper collision or conversion already applied'
    originals[path] = source
    edits[path] = []
    String parsing = source.replaceAll(/(?m)^[ \t]*#include[^\r\n]*/, { match -> ' ' * match.length() })
    for (def method : parse(file.name, parsing)) {
        if (!(method.name ==~ /_(getAllToolDefinitions|toolDisplayMeta|readOnlyToolNames|idempotentWriteToolNames|openWorldToolNames|developerModeOnlyToolNames)_part[A-Za-z0-9_]+/)) continue
        Map record = [file: path, method: method.name]
        try {
            if (method.parameters.length != 0 || !(method.code instanceof BlockStatement) || method.code.statements.size() != 1) {
                throw new IllegalArgumentException('Requires zero arguments and a single return/expression statement')
            }
            def statement = method.code.statements[0]
            if (!(statement instanceof ReturnStatement) && !(statement instanceof ExpressionStatement)) throw new IllegalArgumentException('Not a pure return')
            def expression = statement.expression
            if (!(expression instanceof ListExpression) && !(expression instanceof MapExpression)) throw new IllegalArgumentException('Root is not a literal Map/List')
            if (!(method.returnType.name in ['java.lang.Object', 'java.util.Map', 'java.util.List', 'Map', 'List'])) throw new IllegalArgumentException('Unsupported return declaration')
            String json = JsonOutput.toJson(encode(expression))
            List<String> parts = chunks(json)
            String replacement = reader + '([' + parts.collect { quote(it) }.join(',\n        ') + '])'
            if (method.returnType.name in ['java.util.Map', 'Map']) replacement = '(' + replacement + ' as Map)'
            if (method.returnType.name in ['java.util.List', 'List']) replacement = '(' + replacement + ' as List)'
            int start = offset(source, expression.lineNumber, expression.columnNumber)
            int end = offset(source, expression.lastLineNumber, expression.lastColumnNumber)
            String literal = source.substring(start, end)
            assert literal.startsWith('[') && literal.endsWith(']'): 'Unexpected AST literal span'
            edits[path].add([start: start, end: end, replacement: replacement, method: method.name])
            record.putAll([chunks: parts.size(), jsonBytes: json.getBytes('UTF-8').length])
            selected.add(record)
        } catch (IllegalArgumentException rejection) {
            record.reason = rejection.message
            skipped.add(record)
        }
    }
}
assert selected: 'No literal-safe catalog methods selected'
String mainPath = 'hubitat-mcp-server.groovy'
String main = originals[mainPath]
edits[mainPath].add([start: main.length(), end: main.length(), replacement: '\n' + helperSource, method: '(shared helpers)'])
if (!(main =~ /(?m)^import[ \t]+groovy\.json\.JsonSlurper[ \t]*;?[ \t]*$/).find()) {
    def leading = main =~ /\A(?:\s|\/\/[^\n]*(?:\n|$)|\/\*[\s\S]*?\*\/)*/
    assert leading.find()
    edits[mainPath].add([start: leading.end(), end: leading.end(), replacement: 'import groovy.json.JsonSlurper\n', method: '(explicit import)'])
}

output.mkdirs()
List mapping = []
for (String path : originals.keySet()) {
    String source = originals[path]
    for (Map edit : edits[path].sort { -it.start }) source = source.substring(0, edit.start) + edit.replacement + source.substring(edit.end)
    if (source == originals[path]) continue
    File target = new File(output, 'rewritten/' + path)
    target.parentFile.mkdirs()
    target.setText(source, 'UTF-8')
    assert hash(target.getText('UTF-8')) == hash(source): 'Written source integrity failure'
    mapping.add([path: path, originalSha256: hash(originals[path]), rewrittenSha256: hash(source),
                 originalBytes: originals[path].getBytes('UTF-8').length, rewrittenBytes: source.getBytes('UTF-8').length,
                 edits: edits[path]])
}
Map summary = [status: 'generated', sourceCommit: System.getenv('GITHUB_SHA'), groovy: GroovySystem.version,
               selectedMethods: selected.size(), skippedMethods: skipped.size(), files: mapping.size(),
               chunkModifiedUtf8Budget: chunkBudget, validation: 'Input/output integrity only; actual candidate requires full non-E2E CI',
               encoding: 'tagged JSON arrays with ordered Map key/value pairs; fresh LinkedHashMap/ArrayList decoding']
new File(output, 'replacement-mapping.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(mapping)) + '\n', 'UTF-8')
new File(output, 'selected.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(selected)) + '\n', 'UTF-8')
new File(output, 'skipped.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(skipped)) + '\n', 'UTF-8')
new File(output, 'summary.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(summary)) + '\n', 'UTF-8')
println JsonOutput.prettyPrint(JsonOutput.toJson(summary))
