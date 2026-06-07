// Groovy 2.4 parse + sandbox-class check (issues #105, #209). Runs UNDER Groovy 2.4.21 (the version
// Hubitat's hub runtime uses, resolved by the isolated ci/groovy24-parse Gradle build) against each
// given .groovy file, AFTER resolving its `#include` library bodies (so it sees exactly what the hub
// compiles -- the inlined source, not the raw `#include` directive).
//
// Two gates, both things the Groovy 3.0 test harness misses but the hub enforces at parse time:
//
//  1. GROOVY 2.4 PARSE -- 3.0-only syntax/operators (e.g. null-safe indexing `?[`) that load fine on
//     the Groovy 3.0 Spock harness but fail to load on the 2.4 hub runtime.
//
//  2. BLOCKED-CLASS / SANDBOX CHECK -- the Hubitat sandbox rejects references to non-allowlisted
//     classes at parse time ("Expression [ClassExpression] is not allowed: java.io.InputStream").
//     The Spock harness runs with Flags.DontRestrictGroovy (sandbox OFF, so the stubs work) and a
//     plain Groovy compile doesn't enforce it either -- so an `instanceof InputStream` shipped all
//     the way to a hub deploy before. We compile the inlined source to the CANONICALIZATION phase
//     (where every class reference resolves to its fully-qualified name -- proven to succeed for the
//     production files, which reference only default-imported classes + dynamic hub globals), then
//     walk the AST and fail on any reference (instanceof / cast / new / typed declaration) to a
//     blocked package. Curated blocklist of the dangerous families the hub forbids and the app never
//     uses (java.io, java.nio, reflection, threads, GroovyShell, raw sockets); the app's legitimate
//     java.net.URLEncoder / java.text.SimpleDateFormat etc. are NOT blocked. The real-hub e2e deploy
//     remains the comprehensive backstop for anything not on this list.
//
// If CANONICALIZATION can't resolve a class (e.g. a future file references a hub-only type by name),
// the sandbox check is skipped for that file with a NOTICE and it falls back to the CONVERSION-phase
// syntax gate -- never a false failure.

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression

// Blocked class FQNs. Prefix entries match a whole package subtree; exact entries match one class.
// Curated to dangerous families the Hubitat sandbox forbids AND the production code does not use, so
// there are zero false positives. (Add to this as new blocked classes surface; the e2e deploy is the
// catch-all for the rest.)
def BLOCKED_PREFIXES = ['java.io.', 'java.nio.', 'java.lang.reflect.']
def BLOCKED_EXACT = [
    'java.lang.Thread', 'java.lang.Runtime', 'java.lang.Process', 'java.lang.ProcessBuilder',
    'java.lang.ClassLoader', 'java.lang.SecurityManager',
    'groovy.lang.GroovyShell', 'groovy.lang.GroovyClassLoader', 'groovy.util.Eval',
    'java.net.Socket', 'java.net.ServerSocket', 'java.net.DatagramSocket',
    'java.net.MulticastSocket', 'java.net.URLClassLoader',
] as Set
def isBlocked = { String fqn ->
    if (!fqn) return false
    if (BLOCKED_EXACT.contains(fqn)) return true
    return BLOCKED_PREFIXES.any { fqn.startsWith(it) }
}

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

// Compile to CANONICALIZATION and collect blocked-class references. Returns a list of finding
// strings, or null if CANONICALIZATION could not resolve some class (caller falls back to syntax).
def collectBlocked = { String fileName, String src ->
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(fileName, src)
    try {
        cu.compile(Phases.CANONICALIZATION)
    } catch (Throwable e) {
        def msg = (e.message ?: '')
        if (msg.contains('unable to resolve class') || msg.contains('unable to find')) {
            return null  // a hub-only type referenced by name -> can't run the check; fall back
        }
        throw e          // a genuine compile error -> let the caller report it
    }
    def findings = []
    for (Iterator it = cu.iterator(); it.hasNext();) {
        SourceUnit su = it.next()
        su.AST?.classes?.each { cn ->
            def visitor = new ClassCodeVisitorSupport() {
                protected SourceUnit getSourceUnit() { su }
                private void check(type, int line) {
                    def fqn = type?.name
                    if (isBlocked(fqn)) findings << "line ${line > 0 ? line : '?'}: blocked class reference ${fqn}"
                }
                void visitClassExpression(ClassExpression e) { check(e.type, e.lineNumber); super.visitClassExpression(e) }
                void visitCastExpression(CastExpression e) { check(e.type, e.lineNumber); super.visitCastExpression(e) }
                void visitConstructorCallExpression(ConstructorCallExpression e) { check(e.type, e.lineNumber); super.visitConstructorCallExpression(e) }
                void visitDeclarationExpression(DeclarationExpression e) {
                    if (e.leftExpression instanceof VariableExpression) check(e.leftExpression.type, e.lineNumber)
                    super.visitDeclarationExpression(e)
                }
            }
            cn.visitContents(visitor)
        }
    }
    return findings
}

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

    // Gate 1: Groovy 2.4 syntax (CONVERSION).
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(f.name, resolved)
    try {
        cu.compile(Phases.CONVERSION)
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} parse): ${path}"
        System.err.println e.message
        rc = 1
        continue
    }

    // Gate 2: blocked-class / sandbox check (CANONICALIZATION + AST walk).
    def findings
    try {
        findings = collectBlocked(f.name, resolved)
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} compile): ${path}"
        System.err.println e.message
        rc = 1
        continue
    }
    if (findings == null) {
        println "OK   (Groovy ${GroovySystem.version} parse; sandbox check SKIPPED -- unresolved class refs): ${path}"
    } else if (findings) {
        System.err.println "FAIL (Hubitat sandbox: blocked class reference): ${path}"
        findings.each { System.err.println "  ${it}" }
        System.err.println "  These classes are rejected by the hub at parse time ('ClassExpression not allowed'). Duck-type or use an allowed class."
        rc = 1
    } else {
        println "OK   (Groovy ${GroovySystem.version} parse + sandbox class check): ${path}"
    }
}
System.exit(rc)
