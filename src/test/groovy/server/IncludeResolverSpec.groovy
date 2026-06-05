package server

import spock.lang.Specification
import spock.lang.TempDir
import support.IncludeResolver

/**
 * Unit spec for support.IncludeResolver -- the shared `#include namespace.Name` resolver the CI
 * lanes use to parse/compile what the hub actually runs (issue #209). Pure: no hub sandbox.
 */
class IncludeResolverSpec extends Specification {

    @TempDir
    File tempDir

    private File libsDir() {
        def d = new File(tempDir, 'libraries')
        d.mkdirs()
        return d
    }

    private void writeLib(File dir, String filename, String content) {
        new File(dir, filename).text = content
    }

    def "source with no #include is returned unchanged"() {
        given:
        def src = "definition(name: 'X')\n\ndef foo() { 1 }\n"

        expect:
        IncludeResolver.resolve(src, libsDir()) == src
    }

    def "resolves a #include by namespace+name and inlines the library body with library() stripped"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'smoke.groovy',
            'library(name: "McpSmokeTestLib", namespace: "mcp", author: "x", description: "y")\n\nString mcpSmokeTestMarker() { "smoke-ok-v1" }\n')
        def src = "definition(name: 'X')\n#include mcp.McpSmokeTestLib\ndef foo() { 1 }\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then: 'the #include directive line is gone (it is not valid Groovy)'
        !out.contains('#include')

        and: 'the library body is inlined, sans its library() declaration'
        out.contains('String mcpSmokeTestMarker()')
        !out.contains('library(name:')

        and: 'the rest of the app is preserved'
        out.contains("definition(name: 'X')")
        out.contains('def foo()')
    }

    def "throws (fail loud) on an #include with no matching library"() {
        when:
        IncludeResolver.resolve("#include mcp.NoSuchLib\n", libsDir())

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('mcp.NoSuchLib')
    }

    def "matches libraries by namespace+name parsed from library(), not by filename"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'totally-unrelated-filename.groovy',
            'library(name: "Widget", namespace: "acme")\n\ndef widget() { 42 }\n')
        def src = "#include acme.Widget\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.contains('def widget()')
        !out.contains('#include')
    }

    def "de-duplicates a repeated #include of the same library to a single inlined body"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'm.groovy', 'library(name: "L", namespace: "ns")\n\ndef marker() { "m" }\n')
        def src = "#include ns.L\n#include ns.L\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.count('def marker()') == 1
    }

    def "stripLibraryDeclaration is paren-balanced and survives a ) inside a description literal"() {
        given:
        def lib = 'library(name: "L", namespace: "ns", description: "has a ) paren in it")\n\ndef m() { 1 }\n'

        when:
        def stripped = IncludeResolver.stripLibraryDeclaration(lib)

        then:
        !stripped.contains('library(')
        stripped.contains('def m()')
    }

    def "resolving against the REAL repo libraries dir inlines the live McpSmokeTestLib marker"() {
        given: 'the actual checked-in smoke library (sanity: keeps the resolver honest about the real file)'
        def realLibs = new File('libraries')
        def src = "#include mcp.McpSmokeTestLib\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then:
        out.contains('mcpSmokeTestMarker')
        out.contains('smoke-ok-v1')
        !out.contains('#include')
    }
}
