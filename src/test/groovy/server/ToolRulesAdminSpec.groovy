package server

import support.TestChildApp
import support.TestDevice
import support.ToolSpecBase

/**
 * Spec for the manage_rules_admin gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolDeleteRule -> delete_rule
 * - toolTestRule   -> test_rule
 * - toolExportRule -> export_rule
 * - toolImportRule -> import_rule
 * - toolCloneRule  -> clone_rule
 *
 * Mocking strategy:
 *   - getChildApps() / getChildAppById() / addChildApp() / deleteChildApp()
 *     route through HarnessSpec.wireScriptOverrides()'s reflective
 *     childAppFactory + childAppAccessor wire-up. Specs seed TestChildApp
 *     instances into childAppsList (for lookup + delete) and assign
 *     mockChildAppForCreate (for addChildApp). See docs/testing.md "Which
 *     interception point to use" row 3.
 *   - uploadHubFile(name, bytes) is purely dynamic — stubbed per-test on
 *     script.metaClass when tool code invokes it (e.g. delete_rule backup).
 *   - testRuleFromParent() is MCP-rule-specific (not on TestChildApp by
 *     default) — stubbed per-instance via child.metaClass when needed.
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

    def "import_rule throws when exportData is missing the 'rule' object"() {
        when:
        script.toolImportRule([exportData: [exportVersion: '1.0']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("missing 'rule' object")
    }

    def "import_rule throws when the rule payload has no actions"() {
        given:
        def exportPayload = [
            exportVersion: '1.0',
            rule: [
                name: 'Actionless Rule',
                triggers: [[type: 'time', time: '09:00']],
                actions: []
            ]
        ]

        when:
        script.toolImportRule([exportData: exportPayload])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('at least one action')
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

    def "clone_rule uses the provided name override instead of the 'Copy of' default"() {
        given:
        def source = new TestChildApp(id: 20L, label: 'Backup Check')
        source.ruleData = [
            name: 'Backup Check',
            enabled: true,
            triggers: [[type: 'time', time: '06:00']],
            conditions: [],
            actions: [[type: 'delay', seconds: 1]],
            localVariables: [:]
        ]
        childAppsList << source

        and:
        mockChildAppForCreate = new TestChildApp(id: 21L, label: 'Morning Backup Check')

        when:
        def result = script.toolCloneRule([ruleId: '20', name: '  Morning Backup Check  '])

        then: 'name is trimmed and reflected in the message, no "Copy of" prefix'
        result.success == true
        result.clonedFrom == '20'
        result.message.contains('Morning Backup Check')
        !result.message.contains('Copy of')
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

    def "delete_rule skips auto-backup when the rule is marked testRule=true and tags the message accordingly"() {
        given: 'a rule whose data carries testRule: true'
        def childApp = new TestChildApp(id: 11L, label: 'Test Rule')
        childApp.settingsStore.ruleName = 'Test Rule'
        childApp.ruleData = [
            triggers: [], conditions: [], actions: [], localVariables: [:],
            testRule: true
        ]
        childAppsList << childApp

        and: 'uploadHubFile would fail loudly if called'
        script.metaClass.uploadHubFile = { String fileName, byte[] content ->
            throw new IllegalStateException('uploadHubFile should not have been called for a testRule')
        }

        when: 'delete_rule is invoked without skipBackupCheck'
        def result = script.toolDeleteRule([ruleId: '11', confirm: true])

        then: 'the rule is deleted with the test-rule message suffix and no backup'
        result.success == true
        result.backupFile == null
        result.message.contains('No backup - test rule')
        childAppsList.findAll { it.id?.toString() == '11' } == []
    }

    def "import_rule applies deviceMapping across triggers, conditions, and actions"() {
        given: 'an exported rule that references deviceId 100 in every section'
        def exportPayload = [
            exportVersion: '1.0',
            rule: [
                name: 'Mapped Rule',
                enabled: true,
                // time trigger avoids validateTrigger's device-lookup branch,
                // but still carries a deviceId field so applyDeviceMapping has
                // something to remap
                triggers: [[type: 'time', time: '10:00', deviceId: '100']],
                conditions: [[type: 'variable', variableName: 'mode', operator: 'equals', value: 'Home', deviceId: '100']],
                actions: [[type: 'device_command', deviceId: '100', command: 'on']]
            ]
        ]

        and: 'a findable device at the REMAPPED id so validateAction(device_command) passes'
        childDevicesList << new TestDevice(
            id: 500, name: 'bulb', label: 'Hallway Bulb',
            supportedCommands: [[name: 'on'], [name: 'off']]
        )

        and: 'addChildApp returns the TestChildApp the import will populate'
        def cloned = new TestChildApp(id: 300L, label: 'Mapped Rule')
        mockChildAppForCreate = cloned

        when:
        def result = script.toolImportRule([
            exportData: exportPayload,
            deviceMapping: ['100': '500']
        ])

        then: 'result reports one device mapped'
        result.success == true
        result.imported == true
        result.devicesMapped == 1

        and: 'the deviceId on every section was remapped before being stored on the child app'
        cloned.ruleData.triggers[0].deviceId == '500'
        cloned.ruleData.conditions[0].deviceId == '500'
        cloned.ruleData.actions[0].deviceId == '500'
    }
}
