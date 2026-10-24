package server

import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import spock.lang.Specification
import support.IncludeResolver

// Throwaway measurement: how many JVM classes the inlined parent compiles into.
class ZzClassCountSpec extends Specification {
    def "count compiled classes of the inlined parent"() {
        given:
        File appFile = new File('hubitat-mcp-server.groovy')
        String src = IncludeResolver.resolve(appFile.getText('UTF-8'), new File(appFile.absoluteFile.parentFile, 'libraries'))
        def cu = new CompilationUnit(new CompilerConfiguration())
        cu.addSource('user_app_mcp_MCP_Rule_Server_163', src)

        when:
        cu.compile(Phases.CLASS_GENERATION)
        def classes = cu.classes
        int closures = classes.count { it.name.contains('$_') && it.name.contains('_closure') }
        long bytes = classes.sum { it.bytes.length } as long
        println 'MEASURE-CLASSES ' + JsonOutput.toJson([classes: classes.size(), closureClasses: closures, bytecodeBytes: bytes, sourceChars: src.length()])
        def perMethod = new TreeMap()
        classes.each { c ->
            String n = c.name
            int at = n.indexOf('$_')
            int end = at < 0 ? -1 : n.indexOf('_closure', at + 2)
            if (at >= 0 && end > at) {
                String meth = n.substring(at + 2, end)
                perMethod[meth] = (perMethod[meth] ?: 0) + 1
            }
        }
        println 'MEASURE-PERMETHOD ' + JsonOutput.toJson(perMethod)

        then:
        classes.size() > 0
    }
}
