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
        // Fail with a clear message (not a confusing "no matching library") if cwd ever drifts.
        assert realLibs.isDirectory(), "expected the repo 'libraries' dir relative to cwd ${new File('.').absolutePath} -- run from the repo root"
        def src = "#include mcp.McpSmokeTestLib\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then:
        out.contains('mcpSmokeTestMarker')
        out.contains('smoke-ok-v1')
        !out.contains('#include')
    }

    def "resolving against the REAL repo libraries dir inlines the live McpRoomsLib impls"() {
        given: 'the actual checked-in Rooms library -- first real module of the issue #209 split'
        def realLibs = new File('libraries')
        assert realLibs.isDirectory(), "expected the repo 'libraries' dir relative to cwd ${new File('.').absolutePath} -- run from the repo root"
        def src = "#include mcp.McpRoomsLib\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then: 'the room tool impls are inlined and both the directive and the library() decl are stripped'
        out.contains('def toolListRooms')
        out.contains('def toolRenameRoom')
        !out.contains('#include')
        !out.contains('library(name: "McpRoomsLib"')
    }

    def "resolving against the REAL repo libraries dir inlines the live McpBundlesLib impls + defs"() {
        given: 'the actual checked-in bundle library -- impl AND tool definitions live with it (Level 2)'
        def realLibs = new File('libraries')
        assert realLibs.isDirectory(), "expected the repo 'libraries' dir relative to cwd ${new File('.').absolutePath} -- run from the repo root"
        def src = "#include mcp.McpBundlesLib\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then: 'the bundle tool impls + the def-chunk method are inlined; directive + library() decl stripped'
        out.contains('def toolListBundles')
        out.contains('def toolExportBundle')
        out.contains('def _getAllToolDefinitions_partBundles')
        // The #include DIRECTIVE line is gone (the bundle tool DESCRIPTIONS legitimately contain
        // the text "#include", so assert on the directive line, not the substring).
        !(out =~ /(?m)^\s*#include\b/)
        !out.contains('library(name: "McpBundlesLib"')
    }

    def "resolving against the REAL repo libraries dir inlines the live McpVisualRulesLib impls + defs"() {
        given: 'the actual checked-in Visual Rules library -- impl AND tool definitions live with it'
        def realLibs = new File('libraries')
        assert realLibs.isDirectory(), "expected the repo 'libraries' dir relative to cwd ${new File('.').absolutePath} -- run from the repo root"
        def src = "#include mcp.McpVisualRulesLib\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then: 'the VRB tool impls + the def-chunk method are inlined; directive + library() decl stripped'
        out.contains('def toolGetVisualRule')
        out.contains('def toolSetVisualRule')
        out.contains('def toolDeleteVisualRule')
        out.contains('def _getAllToolDefinitions_partVisualRules')
        !(out =~ /(?m)^\s*#include\b/)
        !out.contains('library(name: "McpVisualRulesLib"')
    }

    def "resolving against the REAL repo libraries dir inlines #libName (impl + defs + metadata parts)"() {
        given: 'every domain library of the #209 full split resolves with impls, def chunk, and display-meta part'
        def realLibs = new File('libraries')
        assert realLibs.isDirectory(), "expected the repo 'libraries' dir relative to cwd ${new File('.').absolutePath} -- run from the repo root"
        def src = "#include mcp.${libName}\n"

        when:
        def out = IncludeResolver.resolve(src, realLibs)

        then:
        out.contains("def ${markerImpl}")
        out.contains("def _getAllToolDefinitions_part${part}")
        out.contains("def _toolDisplayMeta_part${part}")
        !(out =~ /(?m)^\s*#include\b/)
        !out.contains("library(name: \"${libName}\"")

        where:
        libName                 | markerImpl                | part
        'McpFilesLib'           | 'toolListFiles'           | 'Files'
        'McpItemBackupsLib'     | 'toolListItemBackups'     | 'ItemBackups'
        'McpDebugLoggingLib'    | 'toolGetDebugLogs'        | 'DebugLogging'
        'McpDiagnosticsLib'     | 'toolGetHubLogs'          | 'Diagnostics'
        'McpSystemLib'          | 'toolGetHubInfo'          | 'System'
        'McpDevicesLib'         | 'toolListDevices'         | 'Devices'
        'McpVirtualDevicesLib'  | 'toolManageVirtualDevice' | 'VirtualDevices'
        'McpVariablesLib'       | 'toolListVariables'       | 'Variables'
        'McpCustomRulesLib'     | 'toolCreateRule'          | 'CustomRules'
        'McpCodeManagementLib'  | 'toolInstallApp'          | 'CodeManagement'
        'McpHpmLib'             | 'toolListHpmPackages'     | 'Hpm'
        'McpSelfAdminLib'       | 'toolUpdatePackage'       | 'SelfAdmin'
        'McpAppClonerLib'       | 'toolCloneNativeApp'      | 'AppCloner'
        'McpDiscoveryLib'       | 'toolSearchTools'         | 'Discovery'
        'McpNativeRulesLib'     | 'toolSetRule'             | 'NativeRM'
    }

    def "indexLibraries matches name/namespace even when a description ) appears BEFORE the keys"() {
        given: 'regression for the old [^)]* index regex, which a ) in an earlier field truncated'
        def libs = libsDir()
        writeLib(libs, 'w.groovy',
            'library(description: "see foo() for details", name: "Widget", namespace: "acme")\n\ndef widget() { 42 }\n')
        def src = "#include acme.Widget\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.contains('def widget()')
        !out.contains('#include acme')
    }

    def "resolves a multi-line library() declaration"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'ml.groovy',
            'library(\n    name: "Multi",\n    namespace: "ns",\n    description: "spans lines"\n)\n\ndef ml() { 1 }\n')
        def src = "#include ns.Multi\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.contains('def ml()')
        !out.contains('library(')
        !out.contains('#include ns')
    }

    def "resolves CRLF source: directive line removed, body inlined, surrounding lines kept"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'c.groovy', 'library(name: "C", namespace: "ns")\r\n\r\ndef cMethod() { 1 }\r\n')
        def src = "definition(name: 'X')\r\n#include ns.C\r\ndef foo() { 1 }\r\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.contains('def cMethod()')
        out.contains('def foo()')
        !out.contains('#include')
    }

    def "inlines the bodies of MULTIPLE distinct includes, removing every directive, preserving order"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'a.groovy', 'library(name: "A", namespace: "ns")\n\ndef aMethod() { 1 }\n')
        writeLib(libs, 'b.groovy', 'library(name: "B", namespace: "ns")\n\ndef bMethod() { 2 }\n')
        def src = "#include ns.A\nmiddleLine()\n#include ns.B\n"

        when:
        def out = IncludeResolver.resolve(src, libs)

        then:
        out.contains('def aMethod()')
        out.contains('def bMethod()')
        out.contains('middleLine()')
        !out.contains('#include ns')
    }

    def "missing-library error names the requested key AND lists the libraries that ARE present"() {
        given:
        def libs = libsDir()
        writeLib(libs, 'present.groovy', 'library(name: "Present", namespace: "ns")\n\ndef p() { 1 }\n')

        when:
        IncludeResolver.resolve("#include ns.Absent\n", libs)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('ns.Absent')
        e.message.contains('ns.Present')
    }
}
