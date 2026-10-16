// Groovy 2.4 parse + sandbox allowlist check (issues #105, #209). Runs UNDER Groovy 2.4.21 (the version
// Hubitat's hub runtime uses, resolved by the isolated ci/groovy24-parse Gradle build) against each
// given .groovy file, AFTER resolving its `#include` library bodies (so it sees exactly what the hub
// compiles -- the inlined source, not the raw `#include` directive).
//
// Gates for Groovy 2.4 syntax, closure parameters, sandbox-allowed classes, and class size.
//
//  1. GROOVY 2.4 PARSE -- 3.0-only syntax/operators (e.g. null-safe indexing `?[`) that load fine on
//     the Groovy 3.0 Spock harness but fail to load on the 2.4 hub runtime.
//
//  2. SANDBOX ALLOWLIST -- the Hubitat sandbox rejects references to classes outside its allowlist
//     at parse time ("Expression [ClassExpression] is not allowed: java.io.InputStream"), which
//     neither the sandbox-off Spock harness nor a plain compile enforces. We compile to
//     CANONICALIZATION (every class reference resolved to its FQN), walk the AST and fail on any
//     class not in allowed-classes.txt, Hubitat's published list
//     (https://docs2.hubitat.com/en/developer/allowed-imports). Also allowed by rule: primitives,
//     Throwable subclasses, the script's own classes, and the hubitat.* / com.hubitat.* namespaces.
//
// If CANONICALIZATION can't resolve a class (e.g. a future file references a hub-only type by name),
// the sandbox check is skipped for that file with a NOTICE and it falls back to the CONVERSION-phase
// syntax gate. The closure-parameter check still runs on the CONVERSION AST.

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement

// Constant-pool budget for one compiled app class, and bytecode budget for one method (the JVM
// caps both at 65535); see Gate 3 in checkFile.
CLASS_CP_BUDGET = 64000
METHOD_CODE_BUDGET = 60000

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

File allowFile = new File(repoRoot, 'ci/groovy24-parse/allowed-classes.txt')
if (!allowFile.isFile()) {
    System.err.println "MISSING sandbox allowlist: ${allowFile}"
    System.exit(1)
}
// The doc spells nested classes both as Map$Entry and HashMap.Entry; compare with dots.
def ALLOWED = allowFile.readLines('UTF-8')*.trim().findAll { it && !it.startsWith('#') }.collect { it.replace('$', '.') } as Set
def ALLOWED_PACKAGES = ALLOWED.collect { it.substring(0, it.lastIndexOf('.')) } as Set
def PRIMITIVES = ['boolean', 'byte', 'char', 'short', 'int', 'long', 'float', 'double', 'void'] as Set
def HUB_PREFIXES = ['hubitat.', 'com.hubitat.']
ClassNode THROWABLE = ClassHelper.make(Throwable)

def elementType = { ClassNode type ->
    while (type.isArray()) type = type.componentType
    return type
}
// local: dotted names of the classes the compiled source declares.
def isAllowed = { ClassNode type, Set local ->
    type = elementType(type)
    String fqn = type.name.replace('$', '.')
    return PRIMITIVES.contains(fqn) || local.contains(fqn) || ALLOWED.contains(fqn) ||
        HUB_PREFIXES.any { fqn.startsWith(it) } || type.isDerivedFrom(THROWABLE)
}
def isAllowedPackage = { String pkg ->
    ALLOWED_PACKAGES.contains(pkg) || HUB_PREFIXES.any { (pkg + '.').startsWith(it) }
}

// Stock Groovy processes null closure parameters successfully. The hub-specific transform is
// not exercised here. An isolated hub probe rejects { -> 42 } with HTTP 500 while the same
// app with { 42 } compiles; this guard excludes the shape without claiming the private mechanism.
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

// Compile to CANONICALIZATION and collect references to classes outside the sandbox allowlist.
// Returns a list of finding strings, or null if CANONICALIZATION could not resolve some class
// (caller falls back to syntax). Nodes without a source position are compiler-generated and skipped.
def collectBlocked = { CompilationUnit cu, Map resolved ->
    try {
        cu.compile(Phases.CANONICALIZATION)
    } catch (Throwable e) {
        def msg = (e.message ?: '')
        if (msg.contains('unable to resolve class') || msg.contains('unable to find')) {
            return null  // a hub-only type referenced by name -> can't run the check; fall back
        }
        throw e          // a genuine compile error -> let the caller report it
    }
    def findings = new LinkedHashSet()
    def add = { ASTNode node, String name ->
        findings << "${resolverClass.sourceLocation(resolved, node.lineNumber, node.columnNumber)}: blocked class reference ${name}".toString()
    }
    Set local = []
    for (Iterator units = cu.iterator(); units.hasNext();) {
        units.next().AST?.classes?.each { cn -> local << cn.name.replace('$', '.') }
    }
    for (Iterator it = cu.iterator(); it.hasNext();) {
        SourceUnit su = it.next()
        def module = su.AST
        if (module == null) continue
        (module.imports + module.staticImports.values() + module.staticStarImports.values()).each { imp ->
            if (!isAllowed(imp.type, local)) add(imp, elementType(imp.type).name)
        }
        module.starImports.each { imp ->
            String pkg = imp.packageName.endsWith('.') ? imp.packageName[0..-2] : imp.packageName
            if (!isAllowedPackage(pkg)) add(imp, pkg + '.*')
        }
        def visitor = new ClassCodeVisitorSupport() {
            private final Set declared = Collections.newSetFromMap(new IdentityHashMap())
            protected SourceUnit getSourceUnit() { su }
            private void report(ClassNode type, ASTNode node) {
                if (type != null && node.lineNumber > 0 && !isAllowed(type, local)) add(node, elementType(type).name)
            }
            private void parameters(Parameter[] params) {
                for (Parameter p : params ?: []) {
                    report(p.type, p)
                    p.initialExpression?.visit(this)
                }
            }
            // visitClass skips the declared supertypes; compiler-supplied ones carry no position.
            void visitClass(ClassNode node) {
                for (ClassNode type : [node.unresolvedSuperClass] + (node.interfaces as List)) report(type, type)
                super.visitClass(node)
            }
            void visitAnnotations(AnnotatedNode node) {
                for (annotation in node.annotations) report(annotation.classNode, annotation)
                super.visitAnnotations(node)
            }
            protected void visitConstructorOrMethod(MethodNode node, boolean constructor) {
                if (!constructor) report(node.returnType, node)
                parameters(node.parameters)
                super.visitConstructorOrMethod(node, constructor)
            }
            void visitField(FieldNode node) { report(node.type, node); super.visitField(node) }
            void visitClassExpression(ClassExpression e) { report(e.type, e); super.visitClassExpression(e) }
            void visitCastExpression(CastExpression e) { report(e.type, e); super.visitCastExpression(e) }
            void visitConstructorCallExpression(ConstructorCallExpression e) { report(e.type, e); super.visitConstructorCallExpression(e) }
            void visitStaticMethodCallExpression(StaticMethodCallExpression e) { report(e.ownerType, e); super.visitStaticMethodCallExpression(e) }
            void visitClosureExpression(ClosureExpression e) { parameters(e.parameters); super.visitClosureExpression(e) }
            void visitCatchStatement(CatchStatement s) { report(s.variable.type, s); super.visitCatchStatement(s) }
            void visitForLoop(ForStatement s) { report(s.variableType, s); super.visitForLoop(s) }
            void visitDeclarationExpression(DeclarationExpression e) {
                def left = e.leftExpression
                for (v in (left instanceof TupleExpression ? left.expressions : [left])) {
                    if (v instanceof VariableExpression) {
                        declared << v
                        report(v.originType, e)
                    }
                }
                super.visitDeclarationExpression(e)
            }
            // Typed variables declared outside a DeclarationExpression; references are skipped.
            void visitVariableExpression(VariableExpression e) {
                if (!declared.contains(e) && !e.dynamicTyped && (e.accessedVariable == null || e.accessedVariable.is(e))) {
                    report(e.originType, e)
                }
                super.visitVariableExpression(e)
            }
        }
        module.classes.each { visitor.visitClass(it) }
    }
    return findings as List
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

    // Gate 2: sandbox allowlist check (CANONICALIZATION + AST walk).
    def findings
    try {
        findings = collectBlocked(cu, mapped)
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
        System.err.println "  The hub rejects classes outside its sandbox allowlist at parse time (ci/groovy24-parse/allowed-classes.txt). Duck-type or use an allowed class."
        rc = 1
    } else {
        println "OK   (Groovy ${GroovySystem.version} parse + sandbox allowlist check): ${path}"
    }

    // Gate 3: class size. The JVM caps a class at 65535 constant-pool entries, and the hub
    // compiles the app with every #include inlined into one class: past the cap it refuses to
    // load the app at all ("Class too large"), which no parse-level check sees. The hub's own
    // compile adds entries on top of this count: 65,237 here loaded on a C-8, 65,439 did not.
    // A file whose classes did not resolve (the syntax-only fallback above) cannot be compiled to
    // bytecode either, so it is not measured.
    def size = (findings == null) ? [skipped: true] : classSize(f.name, resolved)
    if (size.skipped) {
        println "NOTICE (class size not measured: unresolved class refs): ${path}"
    } else if (size.tooLarge) {
        System.err.println "FAIL (class too large for the JVM): ${path}"
        System.err.println "  ${size.message}"
        rc = 1
    } else if (size.error) {
        System.err.println "FAIL (class size not measured: ${size.error}): ${path}"
        rc = 1
    } else {
        if (size.cp > CLASS_CP_BUDGET) {
            System.err.println "FAIL (class over budget): ${path}: ${size.name} uses ${size.cp} constant-pool entries, budget ${CLASS_CP_BUDGET}"
            System.err.println "  The hub refused to load the app at 65,439 here. Shrink the class before adding code."
            rc = 1
        }
        if (size.method.code > METHOD_CODE_BUDGET) {
            System.err.println "FAIL (method over budget): ${path}: ${size.method.name} is ${size.method.code} bytes of bytecode, budget ${METHOD_CODE_BUDGET}"
            System.err.println "  The JVM refuses a method over 65,535 bytes. Split it before adding code."
            rc = 1
        }
        println "CLASS SIZE ${path}: ${size.name} uses ${size.cp} constant-pool entries (budget ${CLASS_CP_BUDGET}); " +
            "largest method ${size.method.name} is ${size.method.code} bytes (budget ${METHOD_CODE_BUDGET})"
    }

    return rc
}

// Generate bytecode, then report the largest class's constant-pool count and the largest method's
// bytecode length across every generated class (closures included).
def classSize(String name, String source) {
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(name, source)
    try {
        cu.compile(Phases.CLASS_GENERATION)
    } catch (Throwable e) {
        def msg = (e.message ?: e.toString())
        return msg.contains('Class too large') ? [tooLarge: true, message: msg.take(300)] : [error: msg.take(200)]
    }
    def sizes = cu.classes.collect { gc -> classFileSizes(gc.name, gc.bytes) }
    if (!sizes) return [error: 'no classes generated']
    def out = sizes.max { it.cp }
    return [name: out.name, cp: out.cp, method: sizes*.method.max { it.code }]
}

// Walk a class file: the constant pool (count at bytes 8-9), then fields and methods, reading each
// method's Code attribute length.
def classFileSizes(String className, byte[] b) {
    def u2 = { int o -> ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff) }
    def u4 = { int o -> (((long) u2(o)) << 16) | u2(o + 2) }
    int cp = u2(8)
    def utf8 = [:]
    int p = 10
    for (int i = 1; i < cp; i++) {
        int tag = b[p] & 0xff
        if (tag == 1) { int len = u2(p + 1); utf8[i] = new String(b, p + 3, len, 'UTF-8'); p += 3 + len }
        else if (tag in [5, 6]) { p += 9; i++ }
        else if (tag in [3, 4, 9, 10, 11, 12, 17, 18]) p += 5
        else if (tag == 15) p += 4
        else p += 3   // 7, 8, 16, 19, 20
    }
    p += 6
    p += 2 + 2 * u2(p)
    def skipMembers = { boolean methods ->
        def best = [name: null, code: 0]
        int count = u2(p); p += 2
        count.times {
            String member = utf8[u2(p + 2)]
            int attrs = u2(p + 6); p += 8
            attrs.times {
                long len = u4(p + 2)
                if (methods && utf8[u2(p)] == 'Code') {
                    long code = u4(p + 10)
                    if (code > best.code) best = [name: "${className}.${member}", code: code]
                }
                p += 6 + (int) len
            }
        }
        return best
    }
    skipMembers(false)
    return [name: className, cp: cp, method: skipMembers(true)]
}
if (selfTest) {
    def binding = new Binding([checkFile: checkFile, isAllowed: isAllowed, repoRoot: repoRoot, resolverClass: resolverClass])
    System.exit(new GroovyShell(binding).evaluate(new File(repoRoot, 'ci/groovy24-parse/fixtures.groovy')) as int)
}
int rc = 0
args.each { rc |= checkFile(it) }
System.exit(rc)
