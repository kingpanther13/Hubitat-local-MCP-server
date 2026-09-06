// Executed by parse_check.groovy --self-test under the same Groovy 2.4 runtime as the gate.
def cases = [
    ['single-line', 'def f = { -> 1 }', ['1:9']],
    ['compact', 'def f = {->1}', ['1:9']],
    ['argument', 'run({  ->  x() })', ['1:5']],
    ['split-header', 'def f = {\n -> 1 }', ['1:9']],
    ['comment-header', 'def f = { /* explanation */ -> 1 }', ['1:9']],
    ['quoted-literal', "def s = 'literal { -> }'", []],
    ['block-comment', '/* example { -> } */', []],
    ['line-comment', '// example { -> }', []],
    ['url-before-closure', "def url = 'https://x'; def f = { -> 1 }", ['1:32']],
    ['triple-single', "def s = '''literal\n{ -> }'''", []],
    ['triple-double', 'def s = """literal\n{ -> }"""', []],
    ['slashy', 'def s = /literal { -> }/', []],
    ['dollar-slashy', 'def s = $/literal { -> }/$', []],
    ['gstring', 'def s = "value ${ { -> 1 }() }"', ['1:19']],
    ['gstring-lazy', 'def s = "value ${ -> 1 }"', ['1:17']],
    ['gstring-triple', 'def s = """value ${ { -> 1 }() }"""', ['1:21']],
    ['gstring-slashy', 'def s = /value ${ { -> 1 }() }/', ['1:19']],
    ['gstring-dollar-slashy', 'def s = $/value ${ { -> 1 }() }/$', ['1:20']],
    ['nested', 'def f = { x ->\n    { -> { -> x } }\n}', ['2:5', '2:10']],
    ['implicit-it', 'def f = { it }; def g = { 1 }; def h = { return { it } }', []],
    ['explicit', 'def f = { a, b -> a + b }; def g = { String x -> x }', []],
    ['defaulted', 'def f = { x = 1 -> x }; def g = { String x = "ok" -> x }', []],
    ['field-initializer', 'class Sample {\n    def f = { -> 1 }\n}', ['2:13']],
    ['static-initializer', 'class Sample {\n    static { def f = { -> 1 } }\n}', ['2:22']],
    ['object-initializer', 'class Sample {\n    { def f = { -> 1 } }\n}', ['2:15']],
    ['constructor', 'class Sample {\n    Sample() { def f = { -> 1 } }\n}', ['2:24']],
    ['method', 'class Sample {\n    def run() { return { -> 1 } }\n}', ['2:24']],
    ['inner-class', 'class Sample {\n    static class Inner { def f = { -> 1 } }\n}', ['2:34']],
    ['anonymous-class', 'def x = new Object() {\n    def f = { -> 1 }\n}', ['2:13']],
    ['default-expression', 'def work(f = { -> 1 }) {}', ['1:14']],
    ['closure-default-expression', 'def f = { x = { -> 1 } -> x }', ['1:15']],
    ['unresolved-type', 'MissingHubType x\ndef f = { -> 1 }', ['2:9']],
]

File scratch = File.createTempDir('parse24-fixtures-', '')
int failures = 0
def verify = { String name, File file, List expected, String error = null ->
    def bytes = new ByteArrayOutputStream()
    def stream = new PrintStream(bytes, true, 'UTF-8')
    def stdout = System.out
    def stderr = System.err
    int rc
    try {
        System.setOut(stream)
        System.setErr(stream)
        rc = checkFile(file.absolutePath)
    } finally {
        System.setOut(stdout)
        System.setErr(stderr)
        stream.close()
    }
    String output = bytes.toString('UTF-8')
    int count = output.readLines().count { it.contains(': closure parameters are null;') }
    boolean ok = rc == ((expected || error) ? 1 : 0) && count == expected.size()
    expected.each { ok &= output.contains(it + ': closure parameters are null;') }
    if (error) ok &= output.contains(error)
    if (expected) ok &= !output.contains('CLASS_GENERATION') && !output.contains('sandbox check SKIPPED')
    if (!ok) {
        failures++
        println "SELF-TEST FAIL ${name}: rc=${rc}, expected locations=${expected}, error=${error}\n${output}"
    } else {
        println "SELF-TEST PASS ${name}"
        output.readLines().findAll { it.startsWith('FAIL') || it.startsWith('MISSING:') || it.contains(': closure parameters are null;') || it.contains('blocked class reference java.util.ArrayDeque') }.each { println "  ${it}" }
    }
}
try {
    cases.each { name, source, locations ->
        File f = new File(scratch, name + '.groovy')
        f.setText(source, 'UTF-8')
        verify(name, f, locations.collect { f.absolutePath + ':' + it })
    }
    File missing = new File(scratch, 'missing.groovy')
    verify('missing-input', missing, [], 'MISSING: ' + missing.absolutePath)
    verify('directory-input', scratch, [], 'MISSING: ' + scratch.absolutePath)

    File libDir = new File(scratch, 'libraries')
    libDir.mkdir()
    File lib = new File(libDir, 'origin.groovy')
    lib.setText('library(\r\n    name: "Origin", namespace: "test"\r\n)\r\n\r\ndef helper() {\r\n    return { -> 1 }\r\n}\r\n', 'UTF-8')
    File app = new File(scratch, 'app.groovy')
    app.setText('// app\r\n#include test.Origin\r\n#include test.Origin\r\ndef f = { -> 2 }\r\n', 'UTF-8')
    verify('library-and-app-origins', app, [lib.absolutePath + ':6:12', app.absolutePath + ':4:9'])
    def mapped = resolverClass.resolveWithOrigins(app.getText('UTF-8'), libDir, app)
    String expectedResolved = '// app\n// --- inlined library test.Origin (CI/test parity with the hub paste) ---\ndef helper() {\r\n    return { -> 1 }\r\n}\ndef f = { -> 2 }\n'
    assert mapped.source == expectedResolved
    assert resolverClass.resolve(app.getText('UTF-8'), libDir) == expectedResolved
    lib.setText('library(name: "Origin", namespace: "test"); def f = { -> 1 }', 'UTF-8')
    app.setText('#include test.Origin\n', 'UTF-8')
    verify('same-line-library-origin', app, [lib.absolutePath + ':1:53'])
    app.setText('#include test.Absent\n', 'UTF-8')
    verify('missing-library', app, [], 'has no matching library')

    File deque = new File(scratch, 'deque.groovy')
    deque.setText('def q = new ArrayDeque()', 'UTF-8')
    verify('arraydeque', deque, [], 'blocked class reference java.util.ArrayDeque')
    lib.setText('library(name: "Origin", namespace: "test")\ndef helper() { new ArrayDeque() }\n', 'UTF-8')
    app.setText('#include test.Origin\n', 'UTF-8')
    verify('blocked-library-origin', app, [], lib.absolutePath + ':2:16: blocked class reference java.util.ArrayDeque')
} finally {
    assert scratch.canonicalFile.parentFile == new File(System.getProperty('java.io.tmpdir')).canonicalFile
    assert scratch.name.startsWith('parse24-fixtures-')
    scratch.deleteDir()
}
println "Groovy 2.4 parse-check fixtures: ${failures == 0 ? 'PASS' : 'FAIL'}"
return failures ? 1 : 0
