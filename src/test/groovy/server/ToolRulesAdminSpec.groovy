package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the manage_rules_admin gateway tools (hubitat-mcp-server.groovy
 * around line 2321):
 *
 * - toolDeleteRule   -> delete_rule     (line 2321)
 * - toolTestRule     -> test_rule       (line 2404)
 * - toolExportRule   -> export_rule     (line 2433)
 * - toolImportRule   -> import_rule     (line 2464)
 * - toolCloneRule    -> clone_rule      (line 2530)
 *
 * Mocking strategy:
 *   - getChildApps() is routed to childAppsList via HarnessSpec's reflective
 *     childAppAccessor wire-up. The server defines its own getChildAppById()
 *     that calls getChildApps().find { ... }, so seeding a TestChildApp into
 *     childAppsList is enough to satisfy the lookup.
 *   - deleteChildApp(id) is also routed via childAppAccessor ('delete' op)
 *     — removes matching entries from childAppsList.
 *   - addChildApp(...) is routed to mockChildAppForCreate via the reflective
 *     childAppFactory wire-up (used for toolImportRule + toolCloneRule).
 *   - uploadHubFile(name, bytes) is a Hubitat platform API — stubbed per test
 *     on script.metaClass where needed.
 *   - testRuleFromParent() is MCP-rule-specific (not on TestChildApp by
 *     default) — stubbed per test on the instance's metaClass.
 */
class ToolRulesAdminSpec extends ToolSpecBase {

    // -------- toolTestRule --------

    def "test_rule invokes testRuleFromParent on the child app and returns its result"() {
        given:
        def childApp = new TestChildApp(id: 42L, label: 'Motion Test Rule')
        childApp.metaClass.testRuleFromParent = { -> [success: true, message: 'evaluated'] }
        childAppsList << childApp

        when:
        def result = script.toolTestRule('42')

        then:
        result.success == true
        result.message == 'evaluated'
    }

    def "test_rule throws when rule id is unknown"() {
        when:
        script.toolTestRule('9999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found')
        ex.message.contains('9999')
    }

    // -------- toolExportRule --------

    def "export_rule returns export data with rule fields and empty device manifest when no device refs"() {
        given:
        def childApp = new TestChildApp(id: 7L, label: 'Sunset Lights')
        childApp.ruleData = [
            name: 'Sunset Lights',
            description: 'Turn porch light on at sunset',
            enabled: true,
            conditionLogic: 'all',
            triggers: [[type: 'time', time: 'sunset']],
            conditions: [],
            actions: [[type: 'delay', seconds: 0]],
            localVariables: [:]
        ]
        childAppsList << childApp

        when:
        def result = script.toolExportRule([ruleId: '7'])

        then:
        result.exportVersion == '1.0'
        result.rule.name == 'Sunset Lights'
        result.rule.enabled == true
        result.rule.triggers.size() == 1
        result.rule.triggers[0].type == 'time'
        result.rule.actions[0].type == 'delay'
        result.deviceManifest == []
        result.exportedAt != null
    }

    def "export_rule throws when ruleId is missing"() {
        when:
        script.toolExportRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ruleId is required')
    }

    def "export_rule throws when rule id is unknown"() {
        when:
        script.toolExportRule([ruleId: '404'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found')
    }

    // -------- toolImportRule --------

    def "import_rule creates a new rule via addChildApp and tags the result as imported"() {
        given: 'a valid export payload'
        def exportPayload = [
            exportVersion: '1.0',
            rule: [
                name: 'Imported Rule',
                description: '',
                enabled: true,
                triggers: [[type: 'time', time: '09:00']],
                conditions: [],
                actions: [[type: 'delay', seconds: 2]]
            ]
        ]

        and: 'addChildApp will return a new TestChildApp'
        mockChildAppForCreate = new TestChildApp(id: 101L, label: 'Imported Rule')

        when:
        def result = script.toolImportRule([exportData: exportPayload])

        then:
        result.success == true
        result.ruleId == '101'
        result.imported == true
        result.sourceExportVersion == '1.0'
    }

    def "import_rule throws when exportData is missing"() {
        when:
        script.toolImportRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exportData is required')
    }

    def "import_rule throws when the rule payload has no triggers"() {
        given:
        def exportPayload = [
            exportVersion: '1.0',
            rule: [
                name: 'Bad Rule',
                triggers: [],
                actions: [[type: 'delay', seconds: 1]]
            ]
        ]

        when:
        script.toolImportRule([exportData: exportPayload])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('at least one trigger')
    }

    // -------- toolCloneRule --------

    def "clone_rule exports the source and imports it as a disabled copy with 'Copy of' prefix"() {
        given: 'an existing rule that can be exported'
        def source = new TestChildApp(id: 5L, label: 'Original Rule')
        source.ruleData = [
            name: 'Original Rule',
            description: 'Source rule',
            enabled: true,
            conditionLogic: 'all',
            triggers: [[type: 'time', time: '07:30']],
            conditions: [],
            actions: [[type: 'delay', seconds: 3]],
            localVariables: [:]
        ]
        childAppsList << source

        and: 'addChildApp will produce the clone'
        def cloned = new TestChildApp(id: 6L, label: 'Copy of Original Rule')
        mockChildAppForCreate = cloned

        when:
        def result = script.toolCloneRule([ruleId: '5'])

        then:
        result.success == true
        result.ruleId == '6'
        result.clonedFrom == '5'
        result.message.contains('Original Rule')
        result.message.contains('Copy of Original Rule')

        and: 'the clone was force-disabled by toolCloneRule before import'
        cloned.ruleData.enabled == false
        cloned.ruleData.triggers.size() == 1
    }

    def "clone_rule throws when ruleId is missing"() {
        when:
        script.toolCloneRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ruleId is required')
    }

    // -------- toolDeleteRule --------

    def "delete_rule requires confirm=true"() {
        when:
        script.toolDeleteRule([ruleId: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true')
    }

    def "delete_rule auto-backs-up to file manager and removes the child app"() {
        given: 'an existing rule'
        def childApp = new TestChildApp(id: 9L, label: 'Doomed Rule')
        childApp.settingsStore.ruleName = 'Doomed Rule'
        childApp.ruleData = [
            name: 'Doomed Rule',
            enabled: true,
            triggers: [[type: 'time', time: '12:00']],
            conditions: [],
            actions: [[type: 'delay', seconds: 1]],
            localVariables: [:]
        ]
        childAppsList << childApp

        and: 'uploadHubFile records the backup write'
        def uploads = []
        script.metaClass.uploadHubFile = { String fileName, byte[] content ->
            uploads << [name: fileName, size: content.length]
            return true
        }

        when:
        def result = script.toolDeleteRule([ruleId: '9', confirm: true])

        then: 'a backup file was written before deletion'
        uploads.size() == 1
        uploads[0].name.startsWith('mcp_rule_backup_Doomed_Rule_')
        uploads[0].name.endsWith('.json')
        uploads[0].size > 0

        and: 'the child app is gone'
        childAppsList.findAll { it.id?.toString() == '9' } == []

        and: 'result reports success and the backup file name'
        result.success == true
        result.backupFile == uploads[0].name
        result.message.contains('Doomed Rule')
    }

    def "delete_rule skips auto-backup when skipBackupCheck=true"() {
        given:
        def childApp = new TestChildApp(id: 10L, label: 'Skip-Backup Rule')
        childApp.settingsStore.ruleName = 'Skip-Backup Rule'
        childApp.ruleData = [triggers: [], conditions: [], actions: []]
        childAppsList << childApp

        and: 'uploadHubFile would fail loudly if called'
        script.metaClass.uploadHubFile = { String fileName, byte[] content ->
            throw new IllegalStateException('uploadHubFile should not have been called')
        }

        when:
        def result = script.toolDeleteRule([ruleId: '10', confirm: true, skipBackupCheck: true])

        then:
        result.success == true
        result.backupFile == null
        childAppsList.findAll { it.id?.toString() == '10' } == []
    }

    def "delete_rule throws when ruleId is unknown"() {
        when:
        script.toolDeleteRule([ruleId: '9999', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found')
    }
}
