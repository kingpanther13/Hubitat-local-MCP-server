// Groovy 2.4 parse smoke-check (issue #105 PR2a). Runs UNDER Groovy 2.4.21 (resolved by the
// isolated ci/groovy24-parse Gradle build) and parses each given .groovy file to the CONVERSION
// phase using the 2.4 grammar. This catches code that compiles on the 3.0 test harness but would
// fail to LOAD on Hubitat's Groovy 2.4 hub runtime (3.0-only syntax/operators like null-safe
// indexing `?[`). It stops at CONVERSION because only grammar/syntax matters here; this dynamic
// (non-@CompileStatic) code never resolves its Hubitat-injected globals (log, state, render, ...)
// at compile time anyway, so they are not false positives -- the production files also carry zero
// imports, so there is nothing else to resolve.
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases

int rc = 0
if (!args) {
    System.err.println "usage: parse_check.groovy <file.groovy> [<file.groovy> ...]"
    System.exit(2)
}
for (String path : args) {
    def f = new File(path)
    if (!f.exists()) {
        System.err.println "MISSING: ${path}"
        rc = 1
        continue
    }
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(f)
    try {
        cu.compile(Phases.CONVERSION)
        println "OK   (Groovy ${GroovySystem.version} parse): ${path}"
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} parse): ${path}"
        System.err.println e.message
        rc = 1
    }
}
System.exit(rc)
