package hubitat.helper

/**
 * BUILD-TIME STUB of Hubitat's `hubitat.helper.RMUtils` helper. Lives on
 * `src/main/groovy/` solely so HubitatCI's SandboxClassLoader can resolve
 * the class when sandbox-loaded production code references it.
 *
 * **Not deployed to Hubitat.** `packageManifest.json` deploys only the
 * top-level `hubitat-mcp-server.groovy` and `hubitat-mcp-rule.groovy`
 * files via raw GitHub URLs; nothing under `src/main/groovy/` is shipped.
 * On a real hub, `hubitat.helper.RMUtils` is the platform-provided class.
 *
 * Method signatures mirror the surface used by PR #79's
 * `manage_rule_machine` gateway tools (list, run, pause, resume, boolean).
 * Real behaviour is wired per-test via metaclass from
 * `support.RMUtilsMock`.
 *
 * Why this isn't in `src/test/groovy/support/stubs/`: HubitatCI's
 * SandboxClassLoader (extends `java.lang.ClassLoader`, not
 * `URLClassLoader`) doesn't see classes compiled from the test
 * source-set when loading production-script Groovy. Test-classpath
 * stubs only resolve for direct test-JVM calls — sandbox-loaded
 * production code raises `NoClassDefFoundError`. The main source-set
 * is on the sandbox's parent chain, so stubs here are visible to both
 * the sandbox and direct test calls.
 */
class RMUtils {
    static List getRuleList(String version = '5.0') { return [] }
    static void sendAction(Long ruleId, String action) { }
    static void pauseRule(Long ruleId) { }
    static void resumeRule(Long ruleId) { }
    static void setRuleBoolean(Long ruleId, Boolean value) { }
}
