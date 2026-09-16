package server

import support.TestHub
import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared
import spock.lang.Unroll
import groovy.json.JsonSlurper

/**
 * Spec for the Hub Mesh tools in libraries/mcp-system-lib.groovy (issue #431 item 2):
 *   toolGetHubMesh    -> hub_get_hub_mesh    (read)
 *   toolUpdateHubMesh -> hub_update_hub_mesh (PATCH-like write)
 *
 * Hub Mesh is Hubitat's hub-to-hub device/variable sharing between hubs on the same LAN --
 * NOT the Z-Wave/Zigbee radio mesh. Wire format RE'd from the Vue Hub Mesh page in
 * resources/hub2-source/vue-hub2.min.js (catalogued in that folder's README):
 *
 *   READ:  GET /hub2/hubMeshJson -> {hubList, hubMeshToken, hubMeshEnabled, fullRefreshInterval,
 *          sharedDevices, privateDevices, localLinkedDevices, availableLinkedDevices,
 *          localHubVariables, sharedHubVariables, localLinkedHubVariables,
 *          availableLinkedHubVariables, modeHubId, migrationSourceHubs, migrationDestinationHubs}
 *   WRITE: GET /hub/advanced/{enable,disable}HubMesh          (reboot required to take effect)
 *          GET /device/setHubMeshFullRefreshInterval/<0|120|300|3600>
 *          GET /device/followModes/<hubId|none>
 *          POST /device/setHubMeshToken {"hubId": <number|string>, "token": "..."}
 *
 * The GETs are stubbed via hubGet.register; the POST is captured via
 * script.metaClass.hubInternalPostJson (the dispatch cheat sheet in docs/testing.md).
 */
class ToolHubMeshSpec extends ToolSpecBase {

    // A full /hub2/hubMeshJson payload -- every key the Vue populatePage() reads.
    private static final String MESH_JSON = '''{
        "hubList": [
            {"name": "Loft Hub", "ipAddress": "192.168.1.42", "hubId": 12},
            {"name": "Garage Hub", "ipAddress": "192.168.1.43", "hubId": 13, "warning": "Not connected"}
        ],
        "hubMeshToken": "tok-abc-123",
        "hubMeshEnabled": true,
        "fullRefreshInterval": 300,
        "sharedDevices": [{"id": 101, "name": "Porch Light", "childCount": 0}],
        "privateDevices": [
            {"id": 201, "name": "Private A", "childCount": 0},
            {"id": 202, "name": "Private B", "childCount": 0},
            {"id": 203, "name": "Private C", "childCount": 0}
        ],
        "localLinkedDevices": [{"id": 301, "name": "Loft Lamp", "childCount": 0, "appsUsing": ["Evening Rule"]}],
        "availableLinkedDevices": [
            {"hubId": 12, "hubName": "Loft Hub", "deviceId": 401, "deviceDisplayName": "Loft Fan"}
        ],
        "localHubVariables": [{"name": "alpha", "type": "string"}, {"name": "beta", "type": "number"}],
        "sharedHubVariables": [{"name": "alpha", "type": "string"}],
        "localLinkedHubVariables": [{"name": "loftTemp", "type": "number", "hubName": "Loft Hub", "hubId": 12}],
        "availableLinkedHubVariables": [{"name": "loftMode", "type": "string", "hubName": "Loft Hub", "hubId": 12}],
        "modeHubId": 12,
        "migrationSourceHubs": [],
        "migrationDestinationHubs": []
    }'''

    @Shared private TestLocation sharedLocation = new TestLocation()

    private Map posted   // captured POST: [path: ..., body: <parsed JSON map>, raw: <json text>]

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub()
        posted = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posted.path = path
            posted.raw = body
            posted.body = new JsonSlurper().parseText(body)
            return [success: true]
        }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
    }

    // -----------------------------------------------------------------------
    // hub_get_hub_mesh -- read
    // -----------------------------------------------------------------------

    def "hub_get_hub_mesh parses a full hubMeshJson payload into the flat result shape"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when:
        def result = script.toolGetHubMesh([:])

        then: 'scalars come through, modeHubId is stringified'
        result.success == true
        result.hubMeshEnabled == true
        result.fullRefreshInterval == 300
        result.modeHubId == '12'

        and: 'peers pass through with the hub\'s own keys intact (including the optional warning)'
        result.peers.size() == 2
        result.peers[0].name == 'Loft Hub'
        result.peers[0].ipAddress == '192.168.1.42'
        result.peers[0].hubId == 12
        result.peers[1].warning == 'Not connected'

        and: 'both device-sharing directions plus the not-yet-linked catalog'
        result.sharedDevices*.name == ['Porch Light']
        result.localLinkedDevices[0].appsUsing == ['Evening Rule']
        result.availableLinkedDevices[0].deviceDisplayName == 'Loft Fan'

        and: 'the hub-variable analogues'
        result.sharedHubVariables*.name == ['alpha']
        result.localLinkedHubVariables[0].hubName == 'Loft Hub'
        result.availableLinkedHubVariables*.name == ['loftMode']
    }

    def "hub_get_hub_mesh returns privateDevices and localHubVariables as COUNTS only"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when:
        def result = script.toolGetHubMesh([:])

        then: 'the sizes are reported'
        result.privateDeviceCount == 3
        result.localHubVariableCount == 2

        and: 'the lists themselves never reach the response (they can be hundreds of devices)'
        !result.containsKey('privateDevices')
        !result.containsKey('localHubVariables')

        and: 'the note points at the dedicated tools that do carry the detail'
        result.note.contains('hub_list_devices')
        result.note.contains('hub_list_variables')
    }

    def "hub_get_hub_mesh omits hubMeshToken by default and includes it only with include_token=true"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when: 'no args at all'
        def bare = script.toolGetHubMesh(null)

        then: 'the credential key is absent entirely, not merely null'
        !bare.containsKey('hubMeshToken')

        when: 'the opt-in flag is set'
        def withToken = script.toolGetHubMesh([include_token: true])

        then:
        withToken.hubMeshToken == 'tok-abc-123'
    }

    def "hub_get_hub_mesh is null-safe: a firmware that omits every optional key still returns a usable shape"() {
        given: 'a minimal payload -- no lists, no modeHubId, no scalars'
        hubGet.register('/hub2/hubMeshJson') { params -> '{}' }

        when:
        def result = script.toolGetHubMesh([:])

        then: 'lists degrade to empty, unreported scalars to null, and modeHubId to the UI\'s "none"'
        result.success == true
        result.hubMeshEnabled == null
        result.fullRefreshInterval == null
        result.modeHubId == 'none'
        result.peers == []
        result.sharedDevices == []
        result.localLinkedDevices == []
        result.availableLinkedDevices == []
        result.sharedHubVariables == []
        result.localLinkedHubVariables == []
        result.availableLinkedHubVariables == []
        result.privateDeviceCount == 0
        result.localHubVariableCount == 0
    }

    def "hub_get_hub_mesh notes the disable state and how to turn Hub Mesh on"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> '{"hubMeshEnabled": false, "hubList": []}' }

        when:
        def result = script.toolGetHubMesh([:])

        then:
        result.success == true
        result.hubMeshEnabled == false
        result.note.contains('DISABLED')
        result.note.contains('hub_update_hub_mesh')
        result.note.toLowerCase().contains('reboot')
    }

    @Unroll
    def "hub_get_hub_mesh returns the runtime-error contract when the endpoint answers #label"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> response }

        when:
        def result = script.toolGetHubMesh([:])

        then: 'structured error, NOT a throw -- the AI needs something actionable back'
        result.success == false
        result.error
        result.note.contains('firmware')
        result.note.contains('hub_get_radio_details')   // steer away from the radio-mesh confusion

        where:
        label            | response
        'an empty body'  | ''
        'null'           | null
        'a JSON array'   | '[1, 2, 3]'
    }

    def "hub_get_hub_mesh returns the runtime-error contract when the endpoint throws"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('404 Not Found') }

        when:
        def result = script.toolGetHubMesh([:])

        then:
        result.success == false
        result.error.contains('404 Not Found')
        result.note.contains('firmware')
    }

    def "hub_get_hub_mesh returns the runtime-error contract when the body is not JSON"() {
        given:
        hubGet.register('/hub2/hubMeshJson') { params -> '<html>Not Found</html>' }

        when:
        def result = script.toolGetHubMesh([:])

        then:
        result.success == false
        result.error
        result.note.contains('firmware')
    }

    // -----------------------------------------------------------------------
    // hub_update_hub_mesh -- validation (all of it must fire before ANY hub call)
    // -----------------------------------------------------------------------

    def "no settable field provided throws IllegalArgumentException listing the fields"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('enabled')
        ex.message.contains('full_refresh_interval')
        ex.message.contains('mode_hub_id')
        ex.message.contains('peer_hub_id')
        hubGet.calls.isEmpty()
        posted.isEmpty()
    }

    def "a null args map throws rather than silently succeeding"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh(null)

        then:
        thrown(IllegalArgumentException)
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "full_refresh_interval=#bad is rejected before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh([full_refresh_interval: bad])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('full_refresh_interval')
        hubGet.calls.isEmpty()

        where:
        bad << [42, -1, 60, 'soon', null, 3601]
    }

    @Unroll
    def "enabled=#bad is rejected -- it must be an actual boolean"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh([enabled: bad])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('enabled')
        hubGet.calls.isEmpty()

        where:
        bad << ['true', 'yes', 1, null]
    }

    @Unroll
    def "peer token pair: #label is rejected"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('peer_')
        hubGet.calls.isEmpty()
        posted.isEmpty()

        where:
        label                       | args
        'peer_hub_id without token' | [peer_hub_id: '12']
        'peer_token without id'     | [peer_token: 'tok']
        'an empty peer_hub_id'      | [peer_hub_id: '  ', peer_token: 'tok']
        'an empty peer_token'       | [peer_hub_id: '12', peer_token: '']
    }

    def "an empty mode_hub_id is rejected before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolUpdateHubMesh([mode_hub_id: '   '])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mode_hub_id')
        hubGet.calls.isEmpty()
    }

    def "validation fires before ANY leg runs even when an earlier field is valid"() {
        given: 'a valid enabled leg paired with a bad interval'
        enableWrite()
        hubGet.register('/hub/advanced/enableHubMesh') { params -> "" }

        when:
        script.toolUpdateHubMesh([enabled: true, full_refresh_interval: 42])

        then: 'the -32602 fires first, so the enable leg never reached the hub (safe to retry)'
        thrown(IllegalArgumentException)
        hubGet.calls.isEmpty()
    }

    // -----------------------------------------------------------------------
    // hub_update_hub_mesh -- the legs
    // -----------------------------------------------------------------------

    def "enabled=true fires GET /hub/advanced/enableHubMesh and notes the reboot requirement"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/enableHubMesh') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([enabled: true])

        then:
        result.success == true
        result.applied == ['enabled']
        hubGet.calls.any { it.path == '/hub/advanced/enableHubMesh' }
        result.note.contains('REBOOT')
        result.note.contains('hub_reboot')
    }

    def "enabled=false fires GET /hub/advanced/disableHubMesh"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disableHubMesh') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([enabled: false])

        then:
        result.success == true
        result.applied == ['enabled']
        hubGet.calls.any { it.path == '/hub/advanced/disableHubMesh' }
        hubGet.calls.every { it.path != '/hub/advanced/enableHubMesh' }
        result.note.contains('REBOOT')
    }

    @Unroll
    def "full_refresh_interval=#given fires GET /device/setHubMeshFullRefreshInterval/#expected"() {
        given:
        enableWrite()
        hubGet.register("/device/setHubMeshFullRefreshInterval/${expected}") { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([full_refresh_interval: given])

        then:
        result.success == true
        result.applied == ['full_refresh_interval']
        hubGet.calls.any { it.path == "/device/setHubMeshFullRefreshInterval/${expected}" }
        // an interval-only call must NOT touch enable/disable
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }

        where:
        given  | expected
        300    | 300
        0      | 0
        120    | 120
        3600   | 3600
        '300'  | 300     // a string-typed integer is coerced, not rejected
    }

    def "mode_hub_id='none' fires GET /device/followModes/none (back to local modes)"() {
        given:
        enableWrite()
        hubGet.register('/device/followModes/none') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([mode_hub_id: 'none'])

        then:
        result.success == true
        result.applied == ['mode_hub_id']
        hubGet.calls.any { it.path == '/device/followModes/none' }
    }

    def "mode_hub_id=<peer hubId> fires GET /device/followModes/<hubId>"() {
        given:
        enableWrite()
        hubGet.register('/device/followModes/12') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([mode_hub_id: '12'])

        then:
        result.success == true
        result.applied == ['mode_hub_id']
        hubGet.calls.any { it.path == '/device/followModes/12' }
    }

    def "the peer-token leg POSTs /device/setHubMeshToken with a NUMERIC hubId for an all-digits id"() {
        given:
        enableWrite()

        when:
        def result = script.toolUpdateHubMesh([peer_hub_id: '12', peer_token: 'peer-tok-9'])

        then:
        result.success == true
        result.applied == ['peer_token']
        posted.path == '/device/setHubMeshToken'
        posted.body.token == 'peer-tok-9'

        and: 'the Vue page sends the number it read out of hubMeshJson -- preserve that JSON type'
        posted.body.hubId == 12
        posted.body.hubId instanceof Number
        posted.raw.contains('"hubId":12')
        !posted.raw.contains('"hubId":"12"')
    }

    def "a non-numeric peer_hub_id is posted as a string"() {
        given:
        enableWrite()

        when:
        def result = script.toolUpdateHubMesh([peer_hub_id: 'hub-a1', peer_token: 'tok'])

        then:
        result.success == true
        posted.body.hubId == 'hub-a1'
        posted.raw.contains('"hubId":"hub-a1"')
    }

    def "every leg applies in a stable order and all four land in applied"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/enableHubMesh') { params -> "" }
        hubGet.register('/device/setHubMeshFullRefreshInterval/120') { params -> "" }
        hubGet.register('/device/followModes/none') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([
            peer_token: 'tok', peer_hub_id: '13',   // deliberately passed out of order
            mode_hub_id: 'none', full_refresh_interval: 120, enabled: true
        ])

        then: 'the apply order is the tool\'s own stable order, not the caller\'s map order'
        result.success == true
        result.applied == ['enabled', 'full_refresh_interval', 'mode_hub_id', 'peer_token']
        hubGet.calls*.path == ['/hub/advanced/enableHubMesh',
                               '/device/setHubMeshFullRefreshInterval/120',
                               '/device/followModes/none']
        posted.path == '/device/setHubMeshToken'
    }

    // -----------------------------------------------------------------------
    // hub_update_hub_mesh -- partial-apply failure contract
    // -----------------------------------------------------------------------

    def "a failing leg mid-way returns success=false with the already-committed legs in applied"() {
        given: 'the enable leg succeeds, the interval leg 404s'
        enableWrite()
        hubGet.register('/hub/advanced/enableHubMesh') { params -> "" }
        hubGet.register('/device/setHubMeshFullRefreshInterval/300') { params -> throw new RuntimeException('404 Not Found') }
        hubGet.register('/device/followModes/none') { params -> "" }

        when:
        def result = script.toolUpdateHubMesh([enabled: true, full_refresh_interval: 300, mode_hub_id: 'none'])

        then: 'structured error, not a throw -- these are independent GETs, so partial apply is real'
        result.success == false
        result.error.contains('404 Not Found')
        result.applied == ['enabled']

        and: 'the note names what already committed and how to read the truth back'
        result.note.contains('enabled')
        result.note.contains('hub_get_hub_mesh')

        and: 'the run short-circuits -- the later mode leg is never attempted'
        hubGet.calls.every { it.path != '/device/followModes/none' }
    }

    def "a failing first leg returns success=false with an empty applied"() {
        given:
        enableWrite()
        hubGet.register('/hub/advanced/disableHubMesh') { params -> throw new RuntimeException('connection refused') }

        when:
        def result = script.toolUpdateHubMesh([enabled: false, full_refresh_interval: 0])

        then:
        result.success == false
        result.applied == []
        result.error.contains('disable')
        hubGet.calls.every { !it.path.startsWith('/device/setHubMeshFullRefreshInterval') }
    }

    def "a failing peer-token POST reports the earlier legs in applied"() {
        given:
        enableWrite()
        hubGet.register('/device/followModes/none') { params -> "" }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            throw new RuntimeException('500 Server Error')
        }

        when:
        def result = script.toolUpdateHubMesh([mode_hub_id: 'none', peer_hub_id: '12', peer_token: 'tok'])

        then:
        result.success == false
        result.applied == ['mode_hub_id']
        result.error.contains('500 Server Error')
        result.note.contains('peers')
    }

    // -----------------------------------------------------------------------
    // dispatch envelope
    // -----------------------------------------------------------------------

    def "hub_get_hub_mesh via dispatch returns the read envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when:
        def response = mcpDriver.callTool('hub_get_hub_mesh', [:])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.hubMeshEnabled == true
        inner.fullRefreshInterval == 300
        inner.peers.size() == 2
        inner.privateDeviceCount == 3
        !inner.containsKey('hubMeshToken')

        where:
        useGateways << [true, false]
    }

    def "hub_get_hub_mesh via dispatch honors include_token (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when:
        def response = mcpDriver.callTool('hub_get_hub_mesh', [include_token: true])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.hubMeshToken == 'tok-abc-123'

        where:
        useGateways << [true, false]
    }

    def "hub_update_hub_mesh via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/device/setHubMeshFullRefreshInterval/300') { params -> "" }

        when:
        def response = mcpDriver.callTool('hub_update_hub_mesh', [full_refresh_interval: 300])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.applied == ['full_refresh_interval']
        hubGet.calls.any { it.path == '/device/setHubMeshFullRefreshInterval/300' }

        where:
        useGateways << [true, false]
    }

    def "hub_update_hub_mesh with no args maps to an isError validation result through dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_hub_mesh', [:])

        then: 'an IllegalArgumentException from validation becomes a caller-recoverable isError result'
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('at least one field')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "hub_update_hub_mesh with a bad interval maps to an isError validation result through dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_hub_mesh', [full_refresh_interval: 42])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('full_refresh_interval')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "the Write master blocks hub_update_hub_mesh while the Read master still serves hub_get_hub_mesh"() {
        given:
        settingsMap.enableWrite = false
        settingsMap.enableRead = true
        hubGet.register('/hub2/hubMeshJson') { params -> MESH_JSON }

        when: 'the write is centrally gated at the executeTool chokepoint'
        def blocked = mcpDriver.callTool('hub_update_hub_mesh', [enabled: true])

        then:
        def blockedText = groovy.json.JsonOutput.toJson(mcpDriver.decodeToolCallResponse(blocked))
        blockedText.contains('Write tools are disabled')
        hubGet.calls.every { !it.path.startsWith('/hub/advanced/') }

        when: 'the read is unaffected'
        def allowed = mcpDriver.callTool('hub_get_hub_mesh', [:])

        then:
        mcpDriver.parseInner(allowed).success == true
    }
}
