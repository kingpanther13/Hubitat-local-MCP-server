package support

/**
 * Resolves Hubitat {@code #include namespace.Name} library directives the way the hub does at
 * parse time, so the CI lanes compile/parse what the hub actually runs (issue #209).
 *
 * On the hub, {@code #include mcp.McpSmokeTestLib} pastes the referenced library's body -- with
 * its {@code library(...)} declaration call stripped -- into the app before Groovy compiles it.
 * The {@code #include} line itself is NOT valid Groovy, so a raw parse of the app source fails;
 * this resolver removes each {@code #include} line and appends the matching library's stripped
 * body, producing source that compiles in plain Groovy exactly as it does on the hub.
 *
 * Libraries are matched by (namespace, name) parsed from each file's {@code library(...)} call in
 * the libraries directory -- NOT by filename -- mirroring how Hubitat resolves the directive.
 *
 * Written for the Groovy 2.4 lowest common denominator (the groovy24-parse lane loads this class
 * dynamically; the 3.0 root lane and the 2.5 lane compile it from the shared test source set), so
 * it deliberately avoids 3.0/2.5-only syntax. Pure JDK + core Groovy; no external dependencies.
 */
class IncludeResolver {

    // One #include directive per line: `#include namespace.Name` (leading whitespace tolerated,
    // trailing CR tolerated for CRLF checkouts). (?m) so `^`/`$` anchor to LINE boundaries -- the
    // whole-source find() below relies on it, and a single-line matches() is unaffected.
    private static final java.util.regex.Pattern INCLUDE_LINE =
        ~/(?m)^[ \t]*#include[ \t]+([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)[ \t]*\r?$/

    /** Convenience: resolve the app file against its sibling {@code libraries/} directory. */
    static String resolveFile(File appFile) {
        return resolve(appFile.getText('UTF-8'), new File(appFile.absoluteFile.parentFile, 'libraries'))
    }

    /**
     * Returns {@code source} with every {@code #include} line removed and each referenced
     * library's stripped body appended. Source with no {@code #include} is returned unchanged.
     * Throws if an {@code #include} has no matching library (fail loud -- a silent miss would
     * compile a half-resolved app).
     */
    static String resolve(String source, File librariesDir) {
        if (source == null) return source
        if (!INCLUDE_LINE.matcher(source).find()) {
            return source
        }
        Map index = indexLibraries(librariesDir)
        List bodies = []
        Set seen = new LinkedHashSet()
        StringBuilder kept = new StringBuilder()
        source.eachLine { String line ->
            def m = INCLUDE_LINE.matcher(line)
            if (m.matches()) {
                String key = m.group(1) + '.' + m.group(2)
                if (seen.add(key)) {
                    String body = (String) index[key]
                    if (body == null) {
                        throw new IllegalStateException(
                            "#include " + key + " has no matching library in " + librariesDir +
                            " (matched libraries: " + index.keySet() + ")")
                    }
                    bodies.add(body)
                }
                // drop the #include line (not valid Groovy)
            } else {
                kept.append(line).append('\n')
            }
        }
        if (bodies.isEmpty()) {
            return kept.toString()
        }
        StringBuilder out = new StringBuilder(kept)
        // NB: deliberately no "#include" substring here -- the resolved output must not reintroduce
        // a directive-looking token, and tests assert its absence.
        out.append('\n// ==== inlined library bodies (CI/test parity with the hub directive paste) ====\n')
        bodies.each { String b -> out.append('\n').append(b).append('\n') }
        return out.toString()
    }

    /** namespace.name -> library body (declaration stripped), for every *.groovy in the dir. */
    static Map indexLibraries(File librariesDir) {
        Map index = [:]
        if (librariesDir == null || !librariesDir.isDirectory()) {
            return index
        }
        File[] files = librariesDir.listFiles()
        if (files == null) return index
        for (File f : files) {
            if (!f.isFile() || !f.name.endsWith('.groovy')) continue
            String text = f.getText('UTF-8')
            String ns = firstGroup(text, ~/library\s*\([^)]*\bnamespace:\s*["']([^"']+)["']/)
            String name = firstGroup(text, ~/library\s*\([^)]*\bname:\s*["']([^"']+)["']/)
            if (ns != null && name != null) {
                index[ns + '.' + name] = stripLibraryDeclaration(text)
            }
        }
        return index
    }

    private static String firstGroup(String text, java.util.regex.Pattern p) {
        def m = p.matcher(text)
        return m.find() ? m.group(1) : null
    }

    /**
     * Removes the leading {@code library( ... )} DSL call (Hubitat strips it on #include). Paren-
     * balanced from the opening paren, ignoring parens inside single/double-quoted strings, so a
     * {@code )} in a description literal can't truncate the strip.
     */
    static String stripLibraryDeclaration(String libSource) {
        def m = (~/library\s*\(/).matcher(libSource)
        if (!m.find()) {
            return libSource.trim()
        }
        int start = m.start()
        int parenOpen = libSource.indexOf((int) '(', start)
        if (parenOpen < 0) return libSource.trim()
        int depth = 0
        int close = -1
        boolean inStr = false
        char quote = 0
        int i = parenOpen
        int n = libSource.length()
        while (i < n) {
            char c = libSource.charAt(i)
            if (inStr) {
                if (c == ('\\' as char)) { i += 2; continue }
                if (c == quote) inStr = false
            } else if (c == ('"' as char) || c == ("'" as char)) {
                inStr = true
                quote = c
            } else if (c == ('(' as char)) {
                depth++
            } else if (c == (')' as char)) {
                depth--
                if (depth == 0) { close = i; break }
            }
            i++
        }
        if (close < 0) return libSource.trim()
        return (libSource.substring(0, start) + libSource.substring(close + 1)).trim()
    }
}
