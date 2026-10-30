// Prints the generated main class's constant-pool count, also when it overflows.
import org.codehaus.groovy.control.*
File source = new File(args[0])
CompilationUnit unit = new CompilationUnit(new CompilerConfiguration())
unit.addSource(source.name, source.getText('UTF-8').replaceAll(/(?m)^[ \t]*#include[^\r\n]*/, ''))
try {
    unit.compile(Phases.CLASS_GENERATION)
    unit.classes.findAll { !it.name.contains('$') }.each { println "OK ${it.name} bytes=${it.bytes.length}" }
} catch (Throwable t) {
    Throwable c = t
    while (c != null && !c.class.name.endsWith('ClassTooLargeException')) c = c.cause
    if (c == null && t instanceof org.codehaus.groovy.control.MultipleCompilationErrorsException) {
        t.errorCollector.errors.each { e -> def x = e.respondsTo('getCause') ? e.cause : null; if (x?.class?.name?.endsWith('ClassTooLargeException')) c = x }
    }
    if (c == null) throw t
    println "OVERFLOW constantPoolCount=${c.constantPoolCount} limit=65535"
}
