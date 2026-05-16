package support

/**
 * Guard test for the {@code script.settings} access path.
 *
 * Verified via {@code javap -c} on {@code HubitatAppScript}: the
 * {@code @CompileStatic getProperty(String)} override only short-circuits
 * {@code "params"}, {@code "parent"}, and {@code "request"} — {@code "settings"}
 * falls through to MOP, resolves to {@code getSettings()}, whose bytecode
 * calls callsite #87 on {@code this.api}. So {@code script.settings} reads
 * route through {@code api.getSettings()} → HarnessSpec's per-spec Mock
 * stub → {@code SHARED_SETTINGS_MAP}.
 *
 * This spec asserts that contract holds: a value written to
 * {@code settingsMap} on the test side is visible via {@code script.settings}
 * on the script side. If a future eighty20results upgrade rewires
 * {@code getProperty("settings")} or {@code getSettings()} to bypass
 * {@code api} (e.g. reading directly from the sandbox-bound
 * {@code preferencesReader.userSettingsMap}, which becomes an orphan ref
 * after the first spec's compile), this guard fails loudly and points
 * at the harness rather than letting every consuming spec silently
 * misbehave.
 */
class SettingsRoutingGuardSpec extends HarnessSpec {

    def "script.settings reads route through api.getSettings() — mutations to settingsMap are visible"() {
        given:
        def marker = "harness-routing-${System.nanoTime()}"
        settingsMap.harnessRoutingProbe = marker

        expect:
        script.settings.harnessRoutingProbe == marker
    }
}
