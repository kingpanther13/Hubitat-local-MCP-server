package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the one-time enableCustomRuleEngine rename migration that fires
 * inside updated().
 *
 * Background: the legacy setting was `enableRuleEngine` (defaultValue: true).
 * The setting was renamed to `enableCustomRuleEngine` (defaultValue: false).
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

    // Ordered record of lifecycle wire-up calls. schedule()/unschedule() are
    // class-2 (declared on the eighty20results delegate chain), so a per-instance
    // metaClass stub on the script is bypassed -- they must be recorded via
    // permanent >> dispatchers on the appExecutor mock installed in setupSpec.
    // Tests reset this in given: with .clear().
    @Shared protected final List<String> lifecycleCalls = []

    def setupSpec() {
        // Additive stub layered on top of HarnessSpec.setupSpec's appExecutor.
        // Gives app.updateSetting(...) a non-null target (same pattern as
        // ToolManageLogsSpec). Must be in setupSpec (not given:) because the
        // @Shared Mock's interaction set is read-only after setup() completes.
        appExecutor.getApp() >> sharedAppStub
        // Record schedule/unschedule call order for the schedule-symmetry test.
        appExecutor.schedule(*_) >> { args -> lifecycleCalls << 'schedule' }
        appExecutor.unschedule() >> { lifecycleCalls << 'unschedule' }
        // createAccessToken is class-2 (declared on AppExecutor) -- a per-instance
        // script.metaClass stub would be bypassed -- so stub it here. Models the real
        // OAuth API: it populates state.accessToken. Used by the token-regenerate test.
        appExecutor.createAccessToken() >> { stateMap.accessToken = 'new'; 'new' }
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

    def "updated() forces enableCustomRuleEngine OFF when legacy enableRuleEngine is present and new setting was never set"() {
        given: 'pre-rename install: legacy setting present, new setting absent (null -- never touched by user or firmware)'
        def mcpLogCalls = stubUpdatedDeps()
        settingsMap.enableRuleEngine = true          // legacy setting present
        // enableCustomRuleEngine deliberately NOT set: migration condition is == null

        when:
        script.updated()

        then: 'app.updateSetting was called to force the new setting to false'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == [type: 'bool', value: false]

        and: 'migration marker is set so subsequent calls skip'
        stateMap.customEngineMigrated == true

        and: 'an info log line was emitted for the migration'
        mcpLogCalls.any { it.level == 'info' && it.component == 'engine-migration' && it.msg.contains('one-time rename migration') }
    }

    def "updated() does not overwrite enableCustomRuleEngine when it is already explicitly set to true"() {
        given: 'pre-rename install: legacy setting present, new setting is already explicitly true (user set, not null)'
        def mcpLogCalls = stubUpdatedDeps()
        settingsMap.enableRuleEngine = true          // legacy setting present
        settingsMap.enableCustomRuleEngine = true    // explicitly set -- not null -- migration must not fire

        when:
        script.updated()

        then: 'app.updateSetting was NOT called -- explicitly-set true is preserved'
        sharedAppStub.settingsStore['enableCustomRuleEngine'] == null

        and: 'no migration log line emitted'
        !mcpLogCalls.any { it.component == 'engine-migration' }

        and: 'migration marker is set'
        stateMap.customEngineMigrated == true
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

    // -----------------------------------------------------------------------
    // uninstalled(): tears down in-use registrations + subscriptions + schedule
    // (issue #105 PR2b lifecycle-uninstalled-teardown). Do NOT stubUpdatedDeps()
    // -- that no-ops initialize, irrelevant here; we drive uninstalled() direct.
    // -----------------------------------------------------------------------

    def "uninstalled() removes each tracked in-use var, clears the set, and unsubscribes"() {
        given: 'two tracked in-use registrations and a clean unsubscribe counter'
        UNSUBSCRIBE_CALL_COUNT.set(0)
        atomicStateMap.inUseHubVars = ['v1', 'v2']
        def removed = []
        // class-1 (absent from hubitat_ci jar -> purely dynamic): metaClass wins.
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script.uninstalled()

        then: 'each tracked var was de-registered'
        removed.toSet() == ['v1', 'v2'] as Set

        and: 'the tracking set was cleared'
        atomicStateMap.inUseHubVars == null

        and: 'subscriptions were torn down'
        UNSUBSCRIBE_CALL_COUNT.get() == 1
    }

    def "uninstalled() is a no-op for in-use vars when none are tracked (null/empty), without NPE"() {
        given: 'no tracked registrations'
        UNSUBSCRIBE_CALL_COUNT.set(0)
        atomicStateMap.remove('inUseHubVars')
        def removed = []
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script.uninstalled()

        then: 'no de-registration calls and no exception'
        removed == []
        noExceptionThrown()

        and: 'unsubscribe still fired (best-effort teardown)'
        UNSUBSCRIBE_CALL_COUNT.get() == 1
    }

    // -----------------------------------------------------------------------
    // initialize(): unschedule() must precede schedule() so each lifecycle
    // cycle rebuilds the cron set (lifecycle-schedule-symmetry). Direct call;
    // checkForUpdate/_subscribe*/_refresh* are class-1 script methods.
    // -----------------------------------------------------------------------

    def "initialize() unschedules BEFORE scheduling the daily checkForUpdate"() {
        given:
        lifecycleCalls.clear()
        stateMap.accessToken = 'tok'                  // skip createAccessToken
        stateMap.updateCheck = [checkedAt: 1L]        // suppress the immediate check (gate)
        script.metaClass.checkForUpdate = { -> }
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }

        when:
        script.initialize()

        then: 'both wire-up calls fired, unschedule strictly before schedule'
        lifecycleCalls.indexOf('unschedule') >= 0
        lifecycleCalls.indexOf('schedule') >= 0
        lifecycleCalls.indexOf('unschedule') < lifecycleCalls.indexOf('schedule')
    }

    // -----------------------------------------------------------------------
    // initialize(): immediate checkForUpdate() only on first install
    // (lifecycle-version-check-on-every-save). state.updateCheck is the key
    // handleUpdateCheckResponse writes; null == never checked == first install.
    // -----------------------------------------------------------------------

    def "initialize() runs the immediate version check on first install (state.updateCheck null)"() {
        given:
        lifecycleCalls.clear()
        stateMap.accessToken = 'tok'
        stateMap.remove('updateCheck')                // first install: never checked
        def checkCalls = 0
        script.metaClass.checkForUpdate = { -> checkCalls++ }
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }

        when:
        script.initialize()

        then: 'the immediate check fired for first-install freshness'
        checkCalls == 1
    }

    def "initialize() skips the immediate version check on a routine save (state.updateCheck set)"() {
        given:
        lifecycleCalls.clear()
        stateMap.accessToken = 'tok'
        stateMap.updateCheck = [checkedAt: 1234567890000L]   // already checked before
        def checkCalls = 0
        script.metaClass.checkForUpdate = { -> checkCalls++ }
        script.metaClass._subscribeToAllHubVariables = { -> }
        script.metaClass._refreshHubVarInUseRegistrations = { -> }

        when:
        script.initialize()

        then: 'no GitHub egress on a routine settings save'
        checkCalls == 0
    }

    // -----------------------------------------------------------------------
    // appButtonHandler(regenerateTokenBtn): user-initiated, on-demand token
    // rotation (issue #105 PR2b lifecycle-token-rotation / Q9, UI-only). The
    // token is otherwise stable -- only this button (and the first-install
    // guard) ever calls createAccessToken (class-2, stubbed on appExecutor).
    // -----------------------------------------------------------------------

    def "appButtonHandler regenerates the access token when the regenerate button is pressed"() {
        given: 'an existing token and a captured mcpLog'
        stateMap.accessToken = 'old'
        def logCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            logCalls << [level: level, component: component, msg: msg]
        }

        when:
        script.appButtonHandler('regenerateTokenBtn')

        then: 'createAccessToken (appExecutor stub) re-issued a fresh token'
        stateMap.accessToken == 'new'

        and: 'a warn-level audit line was emitted for the rotation'
        logCalls.any { it.level == 'warn' && it.component == 'server' && it.msg.toLowerCase().contains('token') }
    }

    def "appButtonHandler does nothing to the token for an unknown button name"() {
        given:
        stateMap.accessToken = 'old'
        script.metaClass.mcpLog = { String level, String component, String msg -> }

        when:
        script.appButtonHandler('someUnknownBtn')

        then: 'the token is untouched (no regenerate, no createAccessToken)'
        stateMap.accessToken == 'old'
        noExceptionThrown()
    }
    // (confirmRegenerateTokenPage is static UI: dynamicPage cannot be rendered outside a
    // preferences() reader context in this harness, so its body is compile-validated by the
    // sandbox load; the regenerate behaviour is covered by the appButtonHandler test above.)
}
