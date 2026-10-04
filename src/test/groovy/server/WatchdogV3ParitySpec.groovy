package server

import groovy.json.JsonSlurper
import spock.lang.Unroll

class WatchdogV3ParitySpec extends WatchdogV3Harness {
    static final Map MANUAL_TOOLS = [
        hub_update_app: 'adminUpdateApp', hub_set_mcp_developer_mode: 'adminSetMcpDeveloperMode',
        hub_update_mcp_settings: 'adminUpdateMcpSettings',
        hub_get_source: 'adminGetSource', hub_create_library: 'adminCreateLibrary',
        hub_update_library: 'adminUpdateLibrary', hub_delete_item: 'adminDeleteItem',
        hub_force_delete_app: 'adminForceDeleteInstalledApp', hub_purge_e2e_artifacts: 'adminPurgeE2eArtifacts',
        hub_reboot: 'adminRebootHub', hub_set_app_disabled: 'adminSetAppDisabled',
        hub_get_metrics: 'adminGetMetrics', hub_update_platform: 'adminUpdatePlatform',
        hub_get_memory_history: 'adminGetMemoryHistory', hub_get_hub_logs: 'adminGetHubLogs',
        hub_list_app_instances: 'adminListAppInstances', hub_install_bundle: 'adminInstallBundle',
        hub_list_bundles: 'adminListBundles', hub_delete_bundle: 'adminDeleteBundle',
        hub_get_info: 'adminGetInfo', hub_list_apps: 'adminListApps', hub_list_libraries: 'adminListLibraries',
        hub_get_jobs: 'adminGetJobs', hub_read_file: 'adminReadFile', hub_write_file: 'adminWriteFile',
        hub_create_backup: 'adminCreateBackup', hub_manage_variables: 'adminManageVariables'
    ]

    def 'v3 advertises every v2 manual tool plus the three package tools'() {
        expect:
        script.getAdminToolDefinitions()*.name as Set ==
            (MANUAL_TOOLS.keySet() + ['hub_update_package', 'hub_get_package_deployment', 'hub_set_package_deployment']) as Set
    }

    @Unroll
    def 'manual tool #tool reaches its handler through the MCP envelope'() {
        given:
        Map received
        script.metaClass."${handler}" = { Map args -> received = args; [success: true, marker: tool] }
        when:
        def result = script.processJsonRpcMessage([jsonrpc: '2.0', id: 8, method: 'tools/call',
            params: [name: tool, arguments: [confirm: true, noSave: true]]])
        then:
        result.error == null
        new JsonSlurper().parseText(result.result.content[0].text).marker == tool
        received == [confirm: true, noSave: true]
        where:
        [tool, handler] << MANUAL_TOOLS.collect { k, v -> [k, v] }
    }

    def 'initialization schedules no package work and a deployment runs without it'() {
        when:
        script.initialize()
        then:
        scheduled.empty
        when:
        def result = script.adminUpdatePackage(request())
        tick()
        then:
        result.phase == 'queued'
        job().phase == 'awaiting_verification'
    }

    @Unroll
    def 'package hold refuses competing manual write #tool while status remains readable'() {
        given:
        persisted.packageDeployment = [requestId: 'held', hold: true, workerActive: false,
            phase: 'stopped', startedAt: clock, stageStartedAt: clock]
        boolean called = false
        script.metaClass."${MANUAL_TOOLS[tool]}" = { Map args -> called = true; [success: true] }
        when:
        def result = script.executeAdminTool(tool, [confirm: true])
        then:
        result.success == false
        result.error.contains('deployment')
        result.heldRequestId == 'held'
        result.phase == 'stopped'
        !called
        script.adminGetPackageDeployment([requestId: 'held']).phase == 'stopped'
        where:
        tool << ['hub_update_app', 'hub_create_library', 'hub_update_library', 'hub_delete_item',
                 'hub_force_delete_app', 'hub_purge_e2e_artifacts', 'hub_reboot', 'hub_set_app_disabled',
                 'hub_update_platform', 'hub_install_bundle', 'hub_delete_bundle', 'hub_write_file',
                 'hub_create_backup', 'hub_set_mcp_developer_mode', 'hub_update_mcp_settings', 'hub_get_source']
    }

    def 'a manual write prevents a reentrant package start and releases its claim afterwards'() {
        given:
        Map attempted
        script.metaClass.adminWriteFile = { Map args -> attempted = script.adminUpdatePackage(request()); [success: true] }
        when:
        script.executeAdminTool('hub_write_file', [confirm: true])
        then:
        attempted.success == false
        scheduled.empty
        when:
        def accepted = script.adminUpdatePackage(request())
        then:
        accepted.phase == 'queued'
    }

    def 'a repeated purge reaches the purge latch while a different write is refused as busy'() {
        given:
        int purgeCalls = 0
        Map repeat
        Map other
        script.metaClass.adminWriteFile = { Map args -> throw new AssertionError('a competing write must not run') }
        script.metaClass.adminPurgeE2eArtifacts = { Map args ->
            if (++purgeCalls > 1) return [success: true, inFlight: true]
            repeat = script.executeAdminTool('hub_purge_e2e_artifacts', [confirm: true])
            other = script.executeAdminTool('hub_write_file', [confirm: true])
            [success: true]
        }
        when:
        script.executeAdminTool('hub_purge_e2e_artifacts', [confirm: true])
        then:
        repeat.inFlight
        other.success == false
        other.busy
        other.activeTool == 'hub_purge_e2e_artifacts'
        when: 'the sweep has returned'
        script.metaClass.adminWriteFile = { Map args -> [success: true] }
        then:
        script.executeAdminTool('hub_write_file', [confirm: true]).success
    }

    def 'a forced reboot is never blocked by a hold while an unforced one is'() {
        given:
        persisted.packageDeployment = [requestId: 'held', hold: true, workerActive: true,
            phase: 'updating_app', startedAt: clock, stageStartedAt: clock]
        List reboots = []
        script.metaClass.adminRebootHub = { Map args -> reboots << args; [success: true] }
        expect:
        !script.executeAdminTool('hub_reboot', [confirm: true]).success
        script.executeAdminTool('hub_reboot', [confirm: true, force: true]).success
        reboots == [[confirm: true, force: true]]
    }

    def 'a forced reboot passes while another manual write is running, and an unforced one is refused as busy'() {
        given: 'a purge hung on a wedged hub still holds the manual-write claim'
        Map unforced
        Map forced
        List reboots = []
        script.metaClass.adminRebootHub = { Map args -> reboots << args; [success: true] }
        script.metaClass.adminPurgeE2eArtifacts = { Map args ->
            unforced = script.executeAdminTool('hub_reboot', [confirm: true])
            forced = script.executeAdminTool('hub_reboot', [confirm: true, force: true])
            [success: true]
        }
        when:
        script.executeAdminTool('hub_purge_e2e_artifacts', [confirm: true])
        then:
        unforced.success == false
        unforced.busy
        unforced.activeTool == 'hub_purge_e2e_artifacts'
        forced.success
        reboots == [[confirm: true, force: true]]
    }

    def 'source reads without autosave remain available during a held deployment'() {
        given:
        persisted.packageDeployment = [hold: true]
        script.metaClass.adminGetSource = { Map args -> [source: 'readable', noSave: args.noSave] }
        expect:
        script.executeAdminTool('hub_get_source', [noSave: true]).source == 'readable'
    }
    def 'file writes never schedule recovery including writes to a legacy flag name'() {
        given:
        Map uploaded = [:]
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploaded[name] = new String(bytes, 'UTF-8') }
        when:
        def result = script.executeAdminTool('hub_write_file', [fileName: 'e2e-deadman-v2.json',
            content: '{"armed":false,"intent":"disarm"}', confirm: true])
        then:
        result.success
        uploaded['e2e-deadman-v2.json'] == '{"armed":false,"intent":"disarm"}'
        scheduled.empty
    }

    def 'package source and bundle repositories are independently pinned and bound to the operation'() {
        given:
        String fork = 'https://raw.githubusercontent.com/contributor/Hubitat-local-MCP-server'
        String bundleBase = 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server'
        when:
        def accepted = script.adminUpdatePackage(request() + [baseUrl: fork, bundleBaseUrl: bundleBase])
        def changed = script.adminUpdatePackage(request() + [baseUrl: bundleBase, bundleBaseUrl: bundleBase])
        tick()
        then:
        accepted.phase == 'queued'
        changed.success == false
        job().apps.every { it.url.startsWith(fork + '/' + sha + '/') }
        job().bundleUrl == bundleBase + '/bundle-artifacts/shas/' + sha + '/mcp-libraries.zip'
    }

    def 'operator can release a stopped inactive operation for repair when MCP is broken'() {
        given:
        persisted.packageDeployment = [requestId: 'repair', phase: 'stopped', hold: true,
            workerActive: false, startedAt: clock, stageStartedAt: clock]
        when:
        def unconfirmed = script.adminSetPackageDeployment([requestId: 'repair', confirm: true, abandon: true])
        then:
        unconfirmed.success == false
        job().hold
        when:
        def released = script.adminSetPackageDeployment([requestId: 'repair', confirm: true,
            abandon: true, writesSettled: true])
        then:
        released.phase == 'abandoned'
        !job().hold
    }

    def 'operator acknowledgement cannot release an operation with an active worker'() {
        given:
        persisted.packageDeployment = [requestId: 'repair', phase: 'stopped', hold: true,
            startedAt: clock, stageStartedAt: clock]
        script.PACKAGE_PROGRESS.job = persisted.packageDeployment + [workerActive: true, updatedAt: clock]
        expect:
        !script.adminSetPackageDeployment([requestId: 'repair', confirm: true,
            abandon: true, writesSettled: true]).success
        job().hold
    }

    def 'failed manual requests release the claim without releasing a package hold'() {
        given:
        script.metaClass.adminWriteFile = { Map args -> throw new IllegalStateException('write rejected') }
        when:
        script.executeAdminTool('hub_write_file', [confirm: true])
        then:
        thrown(IllegalStateException)
        when:
        def accepted = script.adminUpdatePackage(request())
        then:
        accepted.phase == 'queued'
    }

    def 'held deployments allow platform polling and variable reads but reject variable writes'() {
        given:
        persisted.packageDeployment = [hold: true]
        List calls = []
        script.metaClass.adminUpdatePlatform = { Map args -> calls << 'poll'; [success: true] }
        script.metaClass.adminManageVariables = { Map args -> calls << args.action; [success: true] }
        expect:
        script.executeAdminTool('hub_update_platform', [statusOnly: true]).success
        script.executeAdminTool('hub_manage_variables', [action: 'get', name: 'lease']).success
        script.executeAdminTool('hub_manage_variables', [action: 'hub_get_variable', name: 'lease']).success
        !script.executeAdminTool('hub_manage_variables', [action: 'set', name: 'lease', confirm: true]).success
        !script.executeAdminTool('hub_manage_variables', [action: 'hub_set_variable', name: 'lease', confirm: true]).success
        calls == ['poll', 'get', 'hub_get_variable']
    }

    def 'package start and release run through the MCP envelope and a missing confirm is invalid params'() {
        when:
        def started = script.processJsonRpcMessage([jsonrpc: '2.0', id: 1, method: 'tools/call',
            params: [name: 'hub_update_package', arguments: request()]])
        def unconfirmed = script.processJsonRpcMessage([jsonrpc: '2.0', id: 2, method: 'tools/call',
            params: [name: 'hub_set_package_deployment', arguments: [requestId: 'test-operation']]])
        tick()
        def released = script.processJsonRpcMessage([jsonrpc: '2.0', id: 3, method: 'tools/call',
            params: [name: 'hub_set_package_deployment', arguments: [requestId: 'test-operation', endpointVerified: true, confirm: true]]])
        then:
        new JsonSlurper().parseText(started.result.content[0].text).phase == 'queued'
        unconfirmed.error.code == -32602
        new JsonSlurper().parseText(released.result.content[0].text).phase == 'complete'
        released.result.isError == false
    }

    @Unroll
    def 'package admission refuses a nonrepository source URL #base'() {
        when:
        script.adminUpdatePackage(request() + [baseUrl: base])
        then:
        thrown(IllegalArgumentException)
        scheduled.empty
        where:
        base << ['http://raw.githubusercontent.com/owner/repo', 'https://example.com/owner/repo',
                 'https://raw.githubusercontent.com/owner/repo/main', 'https://raw.githubusercontent.com/owner/..']
    }

    def 'endpoint health alone cannot release a stopped deployment'() {
        given:
        persisted.packageDeployment = [requestId: 'uncertain', phase: 'stopped', hold: true,
            workerActive: false, startedAt: clock, stageStartedAt: clock]
        when:
        def response = script.adminSetPackageDeployment([requestId: 'uncertain', confirm: true,
            abandon: true, endpointVerified: true])
        then:
        response.success == false
        job().hold
    }

}
