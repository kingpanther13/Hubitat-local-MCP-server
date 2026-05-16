package rules

/**
 * Rule-engine counterpart to {@link support.SettingsRoutingGuardSpec}.
 *
 * RuleHarnessSpec's {@code settingsMap} is per-spec-instance {@code @Shared}
 * rather than JVM-static like HarnessSpec's {@code SHARED_SETTINGS_MAP}, so
 * the FIRST RuleHarnessSpec subclass to load binds its {@code settingsMap}
 * into eighty20results' {@code AppPreferencesReader.userSettingsMap} for the
 * JVM's lifetime. Today {@code script.settings} routes through
 * {@code api.getSettings()} (verified via {@code javap -c}), which the
 * per-spec Mock stub answers with THIS spec's {@code settingsMap} — so the
 * orphaned first-spec reference is unreachable. If a future eighty20results
 * change ever bypasses {@code api.getSettings()}, this guard fails when run
 * as a non-first RuleHarnessSpec subclass.
 *
 * <p><b>Precondition:</b> the value of this guard is contingent on
 * RuleHarnessSpec subclasses running in a stable order with this spec
 * not being the first. Today gradle/Spock order is alphabetical and
 * deterministic so this works fine ({@code rules.RegressionsFromHistorySpec},
 * {@code rules.RuleEngineExecutionSpec}, etc. load before
 * {@code rules.SettingsRoutingGuardSpec}). If a future Gradle or Spock
 * upgrade reshuffles test ordering, or if matrix execution sharding ever
 * lands, hoist {@code RuleHarnessSpec.settingsMap} to a JVM-static
 * {@code SHARED_SETTINGS_MAP} (matching the HarnessSpec pattern) to make
 * this guard order-independent.
 */
class SettingsRoutingGuardSpec extends RuleHarnessSpec {

    def "script.settings reads route through api.getSettings() — mutations to settingsMap are visible"() {
        given:
        def marker = "rule-routing-${System.nanoTime()}"
        settingsMap.ruleRoutingProbe = marker

        expect:
        script.settings.ruleRoutingProbe == marker
    }
}
