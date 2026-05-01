package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the one-time enableCustomRuleEngine rename migration that fires
 * inside updated() (PR #134).
 *
 * Background: the legacy setting was `enableRuleEngine` (defaultValue: true).
 * PR #134 renamed it to `enableCustomRuleEngine` (defaultValue: false).
 * Hubitat firmware upgrades on 2.5.0.x re-evaluate renamed Boolean inputs
 * against their defaultValue and can silently flip a user-set `false` back
 * to `true`. The migration in updated() corrects that flip exactly once,
 * then marks state.customEngineMigrated = true to prevent future firings.
 *
 * Mocking strategy:
 *   - initialize()        -- purely dynamic script method; stubbed via
 *                            script.metaClass in given: so platform helpers
 *                            (createAccessToken, schedule, checkForUpdate)
 *                            don't need to be individually mocked.
 *   - app.updateSetting   -- routes through appExecutor.getApp(), which is
 *                            stubbed in setupSpec() to return sharedAppStub
 *                            (a TestChildApp). The Map overload
 *                            updateSetting(String, Map) stores the Map in
 *                            settingsStore so tests can assert on it.
 *   - mcpLog              -- purely dynamic; captured via script.metaClass
 *                            so tests can assert that a log line was (or was
 *                            not) emitted.
 *   - state.*             -- stateMap (the shared Map backed by AppExecutor).
 *   - settings.*          -- settingsMap (the shared Map backed by sandbox).
 *
 * All five scenarios from the design are covered:
 *   1. Golden path: migration fires and forces OFF.
 *   2. Idempotent: already migrated -- migration block skipped.
 *   3. Fresh install: no legacy setting -- migration block skipped.
 *   4. New setting already false -- migration block skipped (no double-write).
 *   5. Post-migration user toggle ON -- state.customEngineMigrated stays true,
 *      new setting stays true (migration does not undo a deliberate user choice).
 */
class AppLifecycleMigrationSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        // Additive stub layered on top of HarnessSpec.setupSpec's appExecutor.
        // Gives app.updateSetting(...) a non-null target (same pattern as
        // ToolManageLogsSpec). Must be in setupSpec (not given:) because the
        // @Shared Mock's interaction set is read-only after setup() completes.
        appExecutor.getApp() >> sharedAppStub
    }

    def setup() {
        // Clear the settingsStore between tests. stateMap / settingsMap are
        // already cleared by HarnessSpec.setup(), so only the stub-app needs
        // explicit cleanup.
        sharedAppStub.settingsStore.clear()
    }

    // -----------------------------------------------------------------------
    // Helper: install a no-op initialize() stub + mcpLog capture so tests
    // can call script.updated() without triggering platform-method calls
    // (createAccessToken, schedule, checkForUpdate). Returns the capture list.
    // -----------------------------------------------------------------------
    private List<Map> stubUpdatedDeps() {
        // initialize() is a script-defined method -- purely dynamic, per-instance
        // metaClass wins (dispatch cheat sheet class 1).
        script.metaClass.initialize = { -> }
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }
        return mcpLogCalls
    }

    // -----------------------------------------------------------------------
    // 1. Golden path: migration fires and forces enableCustomRuleEngine OFF
    // -----------------------------------------------------------------------

    def "updated() forces enableCustomRuleEngine OFF when legacy enableRuleEngine is present and not yet migrated"() {
        given: 'pre-rename install: legacy setting present, new setting was flipped to true by firmware upgrade, migration not yet run'
        def mcpLogCalls = stubUpdatedDeps()
        settingsMap.enableRuleEngine = true          // legacy setting present
        settingsMap.enableCustomRuleEngine = true    // firmware-upgrade flip victim

        when:
        script.updated()

        then: 'app.updateSetting was called to force the new setting to false'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == [type: 'bool', value: false]

        and: 'migration marker is set so subsequent calls skip'
        stateMap.customEngineMigrated == true

        and: 'an info log line was emitted for the migration'
        mcpLogCalls.any { it.level == 'info' && it.component == 'engine-migration' && it.msg.contains('one-time rename migration') }
    }

    // -----------------------------------------------------------------------
    // 2. Idempotent: already migrated -- block must NOT fire a second time
    // -----------------------------------------------------------------------

    def "updated() skips migration when state.customEngineMigrated is already true"() {
        given: 'migration was already run in a prior updated() call'
        def mcpLogCalls = stubUpdatedDeps()
        stateMap.customEngineMigrated = true
        settingsMap.enableRuleEngine = true          // legacy setting still present
        settingsMap.enableCustomRuleEngine = true    // would be corrected if migration fired

        when:
        script.updated()

        then: 'app.updateSetting was NOT called a second time'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == null

        and: 'no migration log line emitted'
        !mcpLogCalls.any { it.component == 'engine-migration' }

        and: 'migration marker remains true'
        stateMap.customEngineMigrated == true
    }

    // -----------------------------------------------------------------------
    // 3. Fresh install: no legacy setting present -- migration must NOT fire
    // -----------------------------------------------------------------------

    def "updated() skips migration when enableRuleEngine is absent (fresh install, no legacy)"() {
        given: 'fresh post-rename install: no legacy enableRuleEngine setting exists'
        def mcpLogCalls = stubUpdatedDeps()
        // settings.enableRuleEngine is NOT set -- null in settingsMap
        settingsMap.enableCustomRuleEngine = false   // new setting at its default

        when:
        script.updated()

        then: 'app.updateSetting was NOT called'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == null

        and: 'no migration log line emitted'
        !mcpLogCalls.any { it.component == 'engine-migration' }

        and: 'migration marker is still set (updated() always sets it at the end)'
        stateMap.customEngineMigrated == true
    }

    // -----------------------------------------------------------------------
    // 4. New setting already false -- no unnecessary double-write
    // -----------------------------------------------------------------------

    def "updated() skips migration when enableCustomRuleEngine is already false"() {
        given: 'legacy setting present but new setting is already correct (false)'
        def mcpLogCalls = stubUpdatedDeps()
        settingsMap.enableRuleEngine = true          // legacy present
        settingsMap.enableCustomRuleEngine = false   // already correct

        when:
        script.updated()

        then: 'app.updateSetting was NOT called (no redundant write)'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == null

        and: 'no migration log line emitted'
        !mcpLogCalls.any { it.component == 'engine-migration' }

        and: 'migration marker is set'
        stateMap.customEngineMigrated == true
    }

    // -----------------------------------------------------------------------
    // 5. Post-migration user toggle ON -- migration must not undo user choice
    //
    // Scenario: user migrated (state.customEngineMigrated = true), then
    // deliberately toggled enableCustomRuleEngine back to true via the settings
    // page (which fires another updated() call). The migration guard must
    // not overwrite their explicit choice.
    // -----------------------------------------------------------------------

    def "updated() does not undo a deliberate user toggle to ON after migration"() {
        given: 'already migrated; user explicitly toggled the new setting ON'
        def mcpLogCalls = stubUpdatedDeps()
        stateMap.customEngineMigrated = true
        settingsMap.enableRuleEngine = true          // legacy still present
        settingsMap.enableCustomRuleEngine = true    // user deliberate choice

        when:
        script.updated()

        then: 'app.updateSetting was NOT called -- user choice is respected'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == null

        and: 'no migration log line emitted'
        !mcpLogCalls.any { it.component == 'engine-migration' }

        and: 'migration marker stays true'
        stateMap.customEngineMigrated == true
    }
}
