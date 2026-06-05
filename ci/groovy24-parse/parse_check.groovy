// Groovy 2.4 parse smoke-check (issue #105 PR2a). Runs UNDER Groovy 2.4.21 (resolved by the
// isolated ci/groovy24-parse Gradle build) and parses each given .groovy file to the CONVERSION
// phase using the 2.4 grammar. This catches code that compiles on the 3.0 test harness but would
// fail to LOAD on Hubitat's Groovy 2.4 hub runtime (3.0-only syntax/operators like null-safe
// indexing `?[`). It stops at CONVERSION because only grammar/syntax matters here; this dynamic
// (non-@CompileStatic) code never resolves its Hubitat-injected globals (log, state, render, ...)
// at compile time anyway, so they are not false positives -- the production files also carry zero
// imports, so there is nothing else to resolve.
//
// #include resolution (issue #209): the hub pastes `#include namespace.Name` library bodies into
// the app before Groovy compiles, so a raw `#include` line is never seen by the 2.4 runtime. We
// resolve each file through the SHARED support/IncludeResolver before parsing, so this lane parses
// exactly what the hub compiles (the inlined source), not the raw `#include` directive.
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases

int rc = 0
if (!args) {
    System.err.println "usage: parse_check.groovy <file.groovy> [<file.groovy> ...]"
    System.exit(2)
}

// Load the shared include-resolver. Production files are passed by ABSOLUTE path and live at the
// repo root, so the first file's parent is the repo root; the resolver is at a known path under it.
File repoRoot = new File(args[0]).absoluteFile.parentFile
File resolverFile = new File(repoRoot, 'src/test/groovy/support/IncludeResolver.groovy')
if (!resolverFile.exists()) {
    System.err.println "MISSING include-resolver: ${resolverFile}"
    System.exit(1)
}
def resolverClass = new GroovyClassLoader().parseClass(resolverFile)

for (String path : args) {
    def f = new File(path)
    if (!f.exists()) {
        System.err.println "MISSING: ${path}"
        rc = 1
        continue
    }
    String resolved
    try {
        resolved = resolverClass.resolve(f.getText('UTF-8'), new File(f.absoluteFile.parentFile, 'libraries'))
    } catch (Throwable e) {
        System.err.println "FAIL (#include resolve): ${path}"
        System.err.println e.message
        rc = 1
        continue
    }
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(f.name, resolved)
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
