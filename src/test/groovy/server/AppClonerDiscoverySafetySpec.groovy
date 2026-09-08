package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.ToolSpecBase

class AppClonerDiscoverySafetySpec extends ToolSpecBase {

    @Unroll
    def "#era #operation refuses creation when the parent snapshot is #failure"() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def rawRequests = []
        def posts = []
        def disabled = []
        int parentReads = 0
        hubGet.register('/installedapp/configure/json/100') {
            JsonOutput.toJson([app: [id: 100, label: 'Source Rule', parentAppId: 21], childApps: []])
        }
        hubGet.register('/installedapp/configure/json/21') {
            parentReads++
            if (parentReads > 1) {
                return JsonOutput.toJson([app: [id: 21], childApps: [
                    [id: 100, label: 'Source Rule'],
                    [id: 150, label: 'Requested Copy'],
                    [id: 200, label: 'Requested Copy']
                ]])
            }
            if (failure == 'unavailable') throw new IOException('parent snapshot unavailable')
            if (failure == 'invalid JSON') return '<html>parent unavailable</html>'
            if (failure == 'missing app') return '{}'
            if (failure == 'missing childApps') return '{"app":{"id":21}}'
            return '{"app":{"id":21},"childApps":{}}'
        }
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([apps: [
                [data: [id: 150], children: []], [data: [id: 200], children: []]
            ]])
        }
        // Keep the real wizard callable so the regression fails on attempted work,
        // rather than on an unrelated missing HTTP fixture after unsafe admission.
        stubClonerWizard(rawRequests, posts, disabled)
        Map args = [confirm: true, stageDisabled: true, newName: 'Requested Copy']
        if (operation == 'clone') {
            args.sourceAppId = 100
        } else {
            args.parentHintAppId = 100
            args.jsonContent = JsonOutput.toJson([
                appReplacements: ['100': [appLabel: 'Source Rule']], deviceReplacements: [:]
            ])
        }
        String tool = "hub_${operation}_native_app".toString()
        Map rec = [outerTool: tool, leafTool: tool]

        when:
        def result
        if (era == 'modern') {
            result = operation == 'clone' ? script._mrtrCloneNativeAppSlice(rec, args) :
                script._mrtrImportNativeAppSlice(rec, args)
        } else {
            result = operation == 'clone' ? script.toolCloneNativeApp(args) : script.toolImportNativeApp(args)
        }

        then:
        noExceptionThrown()
        parentReads == 1
        rawRequests == []
        posts == []
        disabled == []
        result.success == false
        result.isError == true
        result.newAppId == null
        !result.containsKey('__mrtrContinue')
        "${result.error ?: ''} ${result.note ?: ''}".toLowerCase().contains('parent')
        "${result.error ?: ''} ${result.note ?: ''}".toLowerCase().contains('nothing was created')
        "${result.error ?: ''} ${result.note ?: ''}".toLowerCase().contains('retry')

        where:
        [era, operation, failure] << [['legacy', 'modern'], ['clone', 'import'],
            ['unavailable', 'invalid JSON', 'missing app', 'missing childApps', 'non-list childApps']].combinations()
    }

    @Unroll
    def "#era #operation accepts a verified empty parent child list"() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def rawRequests = []
        def posts = []
        def disabled = []
        int parentReads = 0
        hubGet.register('/installedapp/configure/json/100') {
            JsonOutput.toJson([app: [id: 100, label: 'Source Rule', parentAppId: 21], childApps: []])
        }
        hubGet.register('/installedapp/configure/json/21') {
            parentReads++
            JsonOutput.toJson([app: [id: 21], childApps:
                parentReads == 1 ? [] : [[id: 250, label: 'Requested Copy']]])
        }
        stubClonerWizard(rawRequests, posts, disabled)
        Map args = [confirm: true, newName: 'Requested Copy']
        if (operation == 'clone') {
            args.sourceAppId = 100
        } else {
            args.parentHintAppId = 100
            args.jsonContent = JsonOutput.toJson([
                appReplacements: ['100': [appLabel: 'Source Rule']], deviceReplacements: [:]
            ])
        }
        String tool = "hub_${operation}_native_app".toString()
        Map rec = [outerTool: tool, leafTool: tool]

        when:
        def result
        if (era == 'modern') {
            result = operation == 'clone' ? script._mrtrCloneNativeAppSlice(rec, args) :
                script._mrtrImportNativeAppSlice(rec, args)
        } else {
            result = operation == 'clone' ? script.toolCloneNativeApp(args) : script.toolImportNativeApp(args)
        }

        then:
        noExceptionThrown()
        rawRequests.contains('/installedapp/sysAppApi/appCloner/app/100')
        result.isError != true
        era == 'modern' ? result.__mrtrContinue.kind == "${operation}_native_app" :
            (result.success == true && result.newAppId == 250)
        disabled == []

        where:
        [era, operation] << [['legacy', 'modern'], ['clone', 'import']].combinations()
    }

    @Unroll
    def "new-child discovery #scenario"() {
        given:
        hubGet.register('/installedapp/configure/json/21') {
            JsonOutput.toJson([app: [id: 21],
                childApps: [[id: 100, label: 'Source Rule']] + candidates])
        }

        expect:
        script._appClonerDiscoverNewChild(21, ['100'] as Set, 'Source Rule', hint) == expected

        where:
        scenario                            | candidates                                                                                     | hint             | expected
        'accepts one new ID'                 | [[id: 250, label: 'Source Rule clone']]                                                         | null             | 250
        'accepts one renamed new ID'         | [[id: 250, label: 'Requested Copy']]                                                            | 'Requested Copy' | 250
        'ignores the preexisting source'     | []                                                                                             | 'Source Rule'    | null
        'selects a unique exact hint'        | [[id: 250, label: 'Requested Copy'], [id: 251, label: 'Other Rule']]                              | 'Requested Copy' | 250
        'selects an exact hint after others' | [[id: 250, label: 'Other Rule'], [id: 251, label: 'Requested Copy']]                              | 'Requested Copy' | 251
        'rejects duplicate exact hints'      | [[id: 250, label: 'Requested Copy'], [id: 251, label: 'Requested Copy']]                          | 'Requested Copy' | null
        'rejects duplicate default labels'   | [[id: 250, label: 'Source Rule clone'], [id: 251, label: 'Source Rule clone']]                    | null             | null
        'rejects competing default prefixes' | [[id: 250, label: 'Source Rule clone one'], [id: 251, label: 'Source Rule import two']]           | null             | null
        'does not guess from a prefix'       | [[id: 250, label: 'Source Rule clone unrelated'], [id: 251, label: 'Other Rule']]                 | null             | null
        'does not choose the largest ID'     | [[id: 250, label: 'Unrelated A'], [id: 999, label: 'Unrelated B']]                                 | null             | null
        'rejects an unmatched requested name'| [[id: 250, label: 'Source Rule clone'], [id: 251, label: 'Other Rule']]                            | 'Requested Copy' | null
    }

    private void stubClonerWizard(List rawRequests, List posts, List disabled) {
        hubGet.register('/apps/api/900/app/100') { '<html>source context</html>' }
        hubGet.register('/installedapp/configure/json/900/main') { '_action_href_name|importRule|0' }
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, Integer timeout = 30 ->
            rawRequests << path
            [status: 302, location: '/apps/api/900/app/100', data: '']
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            posts << path
            [status: 200]
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, Integer timeout = 420 ->
            posts << path
            [status: 200]
        }
        script.metaClass.toolSetAppDisabled = { Map args -> disabled << args.appId; [success: true] }
    }
}
