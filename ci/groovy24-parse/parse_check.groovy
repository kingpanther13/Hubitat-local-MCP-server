// Groovy 2.4 parse + sandbox-class check (issues #105, #209). Runs UNDER Groovy 2.4.21 (the version
// Hubitat's hub runtime uses, resolved by the isolated ci/groovy24-parse Gradle build) against each
// given .groovy file, AFTER resolving its `#include` library bodies (so it sees exactly what the hub
// compiles -- the inlined source, not the raw `#include` directive).
//
// Gates for Groovy 2.4 syntax, closure parameters, sandbox classes, and bytecode headroom.
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
// syntax gate. The closure-parameter check still runs on the CONVERSION AST.

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
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
    'java.util.ArrayDeque',
] as Set
def isBlocked = { String fqn ->
    if (!fqn) return false
    if (BLOCKED_EXACT.contains(fqn)) return true
    return BLOCKED_PREFIXES.any { fqn.startsWith(it) }
}

if (!args || (args[0] == '--self-test' && args.length != 2)) {
    System.err.println "usage: parse_check.groovy <file.groovy> [...] | --self-test <repo-root>"
    System.exit(2)
}

// Inputs can be deployed fixtures below the root, including a missing expected file.
boolean selfTest = args[0] == '--self-test'
File repoRoot = new File(selfTest ? args[1] : args[0]).absoluteFile
while (repoRoot != null && !new File(repoRoot, 'src/test/groovy/support/IncludeResolver.groovy').isFile()) {
    repoRoot = repoRoot.parentFile
}
if (repoRoot == null) {
    System.err.println "MISSING include-resolver above input: ${args[-1]}"
    System.exit(1)
}
File resolverFile = new File(repoRoot, 'src/test/groovy/support/IncludeResolver.groovy')
if (!resolverFile.exists()) {
    System.err.println "MISSING include-resolver: ${resolverFile}"
    System.exit(1)
}
def resolverClass = new GroovyClassLoader().parseClass(resolverFile)

// Stock Groovy processes null closure parameters successfully. The hub-specific transform is
// not exercised here; this guard excludes that AST shape without claiming it caused a hub 500.
def collectNullParameters = { CompilationUnit cu, Map resolved ->
    def findings = []
    def seen = Collections.newSetFromMap(new IdentityHashMap())
    for (Iterator it = cu.iterator(); it.hasNext();) {
        SourceUnit su = it.next()
        def visitor = new ClassCodeVisitorSupport() {
            protected SourceUnit getSourceUnit() { su }
            protected void visitConstructorOrMethod(MethodNode node, boolean constructor) {
                node.parameters?.each { it.initialExpression?.visit(this) }
                super.visitConstructorOrMethod(node, constructor)
            }
            void visitClosureExpression(ClosureExpression e) {
                if (e.parameters == null && seen.add(e)) {
                    findings << "${resolverClass.sourceLocation(resolved, e.lineNumber, e.columnNumber)}: closure parameters are null; use a named method or explicit parameters"
                }
                e.parameters?.each { it.initialExpression?.visit(this) }
                super.visitClosureExpression(e)
            }
        }
        su.AST.classes.each { visitor.visitClass(it) }
    }
    return findings
}

// Compile to CANONICALIZATION and collect blocked-class references. Returns a list of finding
// strings, or null if CANONICALIZATION could not resolve some class (caller falls back to syntax).
def collectBlocked = { String fileName, Map resolved ->
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(fileName, resolved.source)
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
                private void check(type, expression) {
                    def fqn = type?.name
                    if (isBlocked(fqn)) findings << "${resolverClass.sourceLocation(resolved, expression.lineNumber, expression.columnNumber)}: blocked class reference ${fqn}"
                }
                void visitClassExpression(ClassExpression e) { check(e.type, e); super.visitClassExpression(e) }
                void visitCastExpression(CastExpression e) { check(e.type, e); super.visitCastExpression(e) }
                void visitConstructorCallExpression(ConstructorCallExpression e) { check(e.type, e); super.visitConstructorCallExpression(e) }
                void visitDeclarationExpression(DeclarationExpression e) {
                    if (e.leftExpression instanceof VariableExpression) check(e.leftExpression.type, e)
                    super.visitDeclarationExpression(e)
                }
            }
            cn.visitContents(visitor)
        }
    }
    return findings
}

// Gate 3: per-method BYTECODE BUDGET.
//
// The JVM caps one method's Code attribute at 65,535 bytes and Hubitat compiles the app WITH its
// own sandbox AST transform, which emits more bytecode than stock Groovy -- so a method that is
// merely CLOSE to the cap here has less headroom than it looks. CONVERSION and CANONICALIZATION
// run before any bytecode exists, so only a CLASS_GENERATION compile can measure this at all.
//
// This is a conservative engineering budget, NOT a discovered Hubitat limit, and it is NOT the
// explanation for any deploy failure observed so far: _rmAddAction was 59,643 bytes on a build the
// hub accepted and 58,390 on a build the hub refused. Keep it as headroom against the hard cap;
// do not read a failure here as a diagnosis of a hub 500.
final int METHOD_BYTECODE_BUDGET = 58941

// Throws CompileFailure on a genuine class-generation error; returns null when only the classfile
// MEASUREMENT fails (a bug here must not block a PR whose code is fine).
def largestMethods = { String fileName, String src ->
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(fileName, src)
    cu.compile(Phases.CLASS_GENERATION)     // a throw here is a real compile failure -- let it out
    def out = []
    cu.classes.each { gclass ->
        def bytes = gclass.bytes
        // Walk the classfile: constant pool, then the method table, reading each Code attribute's
        // code_length. Kept to plain byte arithmetic so this needs no ASM dependency.
        int p = 8
        int cpCount = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
        String[] utf8 = new String[cpCount]
        for (int i = 1; i < cpCount; i++) {
            int tag = bytes[p] & 0xFF; p += 1
            if (tag == 1) {
                int len = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
                utf8[i] = new String(bytes, p, len, 'UTF-8'); p += len
            } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) { p += 2 }
            else if (tag == 15) { p += 3 }
            else if (tag == 5 || tag == 6) { p += 8; i++ }   // long/double take two slots
            else { p += 4 }
        }
        p += 6                                                // access_flags, this_class, super_class
        int ifaces = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2 + ifaces * 2
        def skipAttrs = { int q, int n ->
            for (int i = 0; i < n; i++) {
                q += 2
                int alen = ((bytes[q] & 0xFF) << 24) | ((bytes[q + 1] & 0xFF) << 16) | ((bytes[q + 2] & 0xFF) << 8) | (bytes[q + 3] & 0xFF)
                q += 4 + alen
            }
            return q
        }
        int fields = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
        for (int i = 0; i < fields; i++) { p += 6; int na = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2; p = skipAttrs(p, na) }
        int methods = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
        for (int i = 0; i < methods; i++) {
            p += 2
            int nameIdx = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 4
            int na = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
            for (int a = 0; a < na; a++) {
                int an = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF); p += 2
                int alen = ((bytes[p] & 0xFF) << 24) | ((bytes[p + 1] & 0xFF) << 16) | ((bytes[p + 2] & 0xFF) << 8) | (bytes[p + 3] & 0xFF); p += 4
                if (utf8[an] == 'Code') {
                    int cl = ((bytes[p + 4] & 0xFF) << 24) | ((bytes[p + 5] & 0xFF) << 16) | ((bytes[p + 6] & 0xFF) << 8) | (bytes[p + 7] & 0xFF)
                    out << [cls: gclass.name, method: utf8[nameIdx], bytes: cl]
                }
                p += alen
            }
        }
    }
    return out.sort { -it.bytes }
}

def measureQuietly = { String fileName, String src ->
    try { return largestMethods(fileName, src) }
    catch (Throwable e) {
        if (e.class.name.contains('CompilationFailedException')) throw e
        System.err.println "::warning::bytecode-budget MEASUREMENT failed for ${fileName} (${e.class.simpleName}: ${e.message}) -- the budget was not checked"
        return null
    }
}

def checkFile = { String path ->
    int rc = 0
    def f = new File(path)
    if (!f.isFile()) {
        System.err.println "MISSING: ${path}"
        return 1
    }
    Map mapped
    String resolved
    try {
        mapped = resolverClass.resolveWithOrigins(f.getText('UTF-8'), new File(f.absoluteFile.parentFile, 'libraries'), f)
        resolved = mapped.source
    } catch (Throwable e) {
        System.err.println "FAIL (#include resolve): ${path}"
        System.err.println e.message
        return 1
    }

    // Gate 1: Groovy 2.4 syntax (CONVERSION).
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(f.name, resolved)
    try {
        cu.compile(Phases.CONVERSION)
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} parse): ${path}"
        System.err.println e.message
        return 1
    }

    def closureFindings = collectNullParameters(cu, mapped)
    if (closureFindings) {
        System.err.println "FAIL (null closure parameters): ${path}"
        closureFindings.each { System.err.println "  ${it}" }
        return 1
    }

    // Gate 2: blocked-class / sandbox check (CANONICALIZATION + AST walk).
    def findings
    try {
        findings = collectBlocked(f.name, mapped)
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} compile): ${path}"
        System.err.println e.message
        return 1
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

    // Gate 3: per-method bytecode budget.
    try {
        def sizes = measureQuietly(f.name, resolved)
        def worst = (sizes != null && sizes) ? sizes[0] : null
        if (sizes == null) {
            // measurement bug already warned; not a code defect, so do not fail the lane
        } else if (worst == null) {
            println "     (bytecode budget: no methods emitted for ${path})"
        } else if (worst.bytes > METHOD_BYTECODE_BUDGET) {
            System.err.println "FAIL (per-method bytecode budget): ${path}"
            sizes.findAll { it.bytes > METHOD_BYTECODE_BUDGET }.each {
                System.err.println "  ${it.cls}.${it.method}: ${it.bytes} > ${METHOD_BYTECODE_BUDGET} budget (JVM hard cap 65535)"
            }
            System.err.println "  Hubitat's sandbox transform adds bytecode on top of these numbers, so this budget keeps"
            System.err.println "  headroom against the JVM 65,535 hard cap. Split the method -- extracting a cohesive"
            System.err.println "  block into a private helper is enough; #include is a textual paste, so moving code between"
            System.err.println "  the app file and a library does NOT change any method's size."
            rc = 1
        } else {
            println "     (bytecode budget OK: largest ${worst.cls}.${worst.method} = ${worst.bytes} <= ${METHOD_BYTECODE_BUDGET})"
        }
    } catch (Throwable e) {
        System.err.println "FAIL (Groovy ${GroovySystem.version} CLASS_GENERATION): ${path}"
        System.err.println e.message
        rc = 1
    }
    return rc
}
if (selfTest) {
    def binding = new Binding([checkFile: checkFile, repoRoot: repoRoot, resolverClass: resolverClass])
    System.exit(new GroovyShell(binding).evaluate(new File(repoRoot, 'ci/groovy24-parse/fixtures.groovy')) as int)
}
int rc = 0
args.each { rc |= checkFile(it) }
System.exit(rc)
