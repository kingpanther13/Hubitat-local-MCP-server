package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase
import support.TestHub
import support.TestLocation

import static support.BundleRecords.SECRET
import static support.BundleRecords.listJson
import static support.BundleRecords.record

/**
 * Spec for hub_install_bundle (toolInstallBundle) -- the HPM-style bundle install in the
 * hub_manage_code gateway -- and the _installBundleFromUrl helper it shares with
 * hub_update_package's bundle leg.
 *
 * Hub API contracts verified here (record shapes in support.BundleRecords):
 *   GET  /bundle/list/json                              -- read before and after the install
 *   GET  /hub2/userAppTypes, /hub2/userDeviceTypes, /hub2/userLibraries -- read before the install
 *        and, when it fails, after it, to report code a refused install still added
 *   GET  /bundle2/uploadZipFromUrl?url=&pwd=&private=  -- firmware >= 2.3.8.108; {"success":true}, no id
 *   POST /bundle/uploadZipFromUrl  (JSON {url, installer, pwd}) -- older firmware
 *
 * Mocking: hubInternalGet routes through hubGet.register; hubInternalPostJson is stubbed per
 * test on script.metaClass; location.hub is a TestHub carrying the firmware under test.
 */
class ToolInstallBundleSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    private static final String BUNDLE_URL =
        'https://raw.githubusercontent.com/owner/repo/main/bundles/mcp-libraries.zip'

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // backup "within 24h" of fixed now()
    }

    private void modernHub() { sharedLocation.hub = new TestHub(firmwareVersionString: '2.4.0.100') }
    private void oldHub()    { sharedLocation.hub = new TestHub(firmwareVersionString: '2.3.7.140') }

    private static Map bundleRow(int id, String name = 'Throwaway', String url = BUNDLE_URL) {
        record(id: id, name: name, 'private': false, appTypeIds: [id * 10], sourceUrl: url,
               contentReadable: 'apps [Target], drivers [], libraries []')
    }

    private static final List CODE_LISTS = ['/hub2/userAppTypes', '/hub2/userDeviceTypes', '/hub2/userLibraries']

    // Empty code lists, unless a test registers its own.
    private void stubCodeLists() {
        CODE_LISTS.each { path -> hubGet.register(path) { params -> '[]' } }
    }

    // The list answers `before` until the install runs, then `after`.
    private void stubBundleLists(List before, List after) {
        boolean installed = false
        stubCodeLists()
        hubGet.register('/bundle/list/json') { params -> listJson(installed ? after : before) }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> installed = true; '{"success":true,"message":null}' }
    }

    private void stubEmptyList() {
        stubCodeLists()
        hubGet.register('/bundle/list/json') { params -> '[]' }
    }

    private Map createBundle(Map extra = [:]) {
        script.toolInstallBundle([importUrl: BUNDLE_URL, confirm: true] + extra)
    }

    // -------- gating + validation, all before any hub call --------

    def "hub_install_bundle throws when confirm is not provided, before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: BUNDLE_URL])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "hub_install_bundle via dispatch is refused when Write tools are disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_install_bundle via dispatch refuses an unknown argument (#arg) before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, (arg): 'x', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("Unknown hub_install_bundle argument: ${arg}")
        hubGet.calls.isEmpty()

        where:
        useGateways | arg
        true        | 'source'
        false       | 'source'
        true        | 'type'
        false       | 'bundleUrl'
    }

    @Unroll
    def "hub_install_bundle requires an http(s) importUrl: '#url'"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: url, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(message)
        hubGet.calls.isEmpty()

        where:
        url                  | message
        null                 | 'importUrl is required'
        '   '                | 'importUrl is required'
        'ftp://host/x.zip'   | 'scheme must be http or https'
        'file:///etc/passwd' | 'scheme must be http or https'
        'mcp-libraries.zip'  | 'scheme must be http or https'
    }

    def "hub_install_bundle refuses a non-boolean installer before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: 'https://example.com/b.zip', installer: 'yes', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('installer must be true or false')
        hubGet.calls.isEmpty()
    }

    // -------- the new bundle's id --------

    def "hub_install_bundle returns the new bundle and its id from the list diff"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([bundleRow(3, 'Other')], [bundleRow(3, 'Other'), bundleRow(5)])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == '5'
        result.bundle.id == '5'
        result.bundle.name == 'Throwaway'
        result.bundle.namespace == 'mcptest'
        result.bundle['private'] == false
        result.bundle.contains.apps == ['Target']
        result.bundle.codeIds.apps == ['50']
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.installer == false
        result.note == null
        hubGet.calls*.path == ['/bundle/list/json'] + CODE_LISTS + ['/bundle2/uploadZipFromUrl', '/bundle/list/json']
        !JsonOutput.toJson(result).contains(SECRET)
    }

    def "when several bundles appear the one installed from this URL is the new one"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([], [bundleRow(5, 'Other', 'https://example.com/other.zip'), bundleRow(6)])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == '6'
        result.note == null
    }

    def "several new bundles none of which came from this URL leave the id unknown"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([], [bundleRow(5, 'A', 'https://example.com/a.zip'), bundleRow(6, 'B', 'https://example.com/b.zip')])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('No single new bundle appeared')
    }

    @Unroll
    def "hub_install_bundle via dispatch returns the new id (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        stubBundleLists([], [bundleRow(7)])

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.bundleId == '7'
        inner.bundle.name == 'Throwaway'

        where:
        useGateways << [true, false]
    }

    def "a reinstall keeps its id and is found by the URL it was installed from"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([bundleRow(3, 'Other', 'https://example.com/other.zip'), bundleRow(5)],
                        [bundleRow(3, 'Other', 'https://example.com/other.zip'), bundleRow(5)])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == '5'
        result.note.contains('kept its id')
    }

    def "a reinstall of a URL no bundle records still succeeds, with a note and no id"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([bundleRow(5, 'T', 'https://example.com/other.zip')], [bundleRow(5, 'T', 'https://example.com/other.zip')])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('hub_list_bundles')
    }

    def "an unreadable list before the install still installs, skips the second read, and reports the id unknown"() {
        given:
        enableWrite()
        modernHub()
        stubCodeLists()
        hubGet.register('/bundle/list/json') { params -> '<html>login</html>' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":true}' }

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('could not be read before the install')
        hubGet.calls*.path == ['/bundle/list/json'] + CODE_LISTS + ['/bundle2/uploadZipFromUrl']
    }

    def "an unreadable list after the install keeps the success"() {
        given:
        enableWrite()
        modernHub()
        int reads = 0
        stubCodeLists()
        hubGet.register('/bundle/list/json') { params -> reads++ == 0 ? '[]' : { throw new RuntimeException('timeout') }() }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":true}' }

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('could not be read after the install')
    }

    // -------- the hub call --------

    def "modern firmware GETs /bundle2/uploadZipFromUrl with url, empty pwd and the installer flag"() {
        given:
        enableWrite()
        modernHub()
        Map captured = null
        stubEmptyList()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> captured = params; '{"success":true}' }

        when:
        def result = script.toolInstallBundle([importUrl: "  ${BUNDLE_URL}  ".toString(), installer: installer, confirm: true])

        then:
        captured.url == BUNDLE_URL
        captured.pwd == ''
        captured['private'] == expected
        result.success == true
        result.installer == (installer == true)

        where:
        installer | expected
        null      | 'false'
        true      | 'true'
    }

    def "an unreadable firmware version defaults to the modern endpoint"() {
        given:
        enableWrite()
        sharedLocation.hub = new TestHub()
        stubBundleLists([], [bundleRow(5)])

        when:
        def result = createBundle()

        then:
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.success == true
    }

    def "old firmware POSTs JSON to /bundle/uploadZipFromUrl"() {
        given:
        enableWrite()
        oldHub()
        stubEmptyList()
        String capturedPath = null
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            capturedPath = path
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
        }

        when:
        def result = createBundle(installer: true)

        then:
        capturedPath == '/bundle/uploadZipFromUrl'
        capturedBody == [url: BUNDLE_URL, installer: true, pwd: '']
        result.success == true
        result.endpoint == '/bundle/uploadZipFromUrl'
    }

    def "a refused install returns the hub's own reason and the raw reply"() {
        given:
        enableWrite()
        modernHub()
        stubEmptyList()
        // The hub's reply for a zip it cannot fetch, as captured live.
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false,"message":"Cannot retrieve zip file from https://example.com/missing.zip"}' }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error == 'Bundle install failed: Cannot retrieve zip file from https://example.com/missing.zip'
        result.rawResponse.contains('Cannot retrieve zip file')
        result.endpoint == '/bundle2/uploadZipFromUrl'
        !result.containsKey('codeAdded')
        result.note.contains("hub_get_tool_guide(section='bundles')")
        hubGet.calls*.path == ['/bundle/list/json'] + CODE_LISTS + ['/bundle2/uploadZipFromUrl'] + CODE_LISTS
    }

    def "a refused install reports the code the hub still added"() {
        given: 'the hub installs the zip\'s app, then refuses its library name'
        enableWrite()
        modernHub()
        boolean installed = false
        hubGet.register('/bundle/list/json') { params -> '[]' }
        hubGet.register('/hub2/userAppTypes') { params ->
            JsonOutput.toJson([[id: 1, name: 'Old App']] + (installed ? [[id: 846, name: 'PR Review App']] : []))
        }
        hubGet.register('/hub2/userDeviceTypes') { params -> '[]' }
        hubGet.register('/hub2/userLibraries') { params -> '[]' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params ->
            installed = true
            '{"success":false,"message":"name must consist of letters, numbers, underscores, dashes and dots"}'
        }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error.contains('name must consist of letters')
        result.codeAdded == [[type: 'app', id: '846', name: 'PR Review App']]
        result.note.contains('codeAdded')
        result.note.contains('hub_delete_item')
    }

    def "a code list that cannot be read after a refused install is named"() {
        given:
        enableWrite()
        modernHub()
        int libReads = 0
        stubEmptyList()
        hubGet.register('/hub2/userLibraries') { params -> if (libReads++ > 0) throw new RuntimeException('busy'); '[]' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false}' }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.note.contains('library code list could not be read')
    }

    @Unroll
    def "a failed install via dispatch is an isError result, not a protocol error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        stubEmptyList()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false}' }

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('no success signal')

        where:
        useGateways << [true, false]
    }

    def "a thrown hub call is a failure whose outcome is reported unknown"() {
        given:
        enableWrite()
        modernHub()
        stubEmptyList()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> throw new RuntimeException('connection refused') }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error.contains('connection refused')
        result.note.contains('unknown')
    }

    // The hub installs the bundle and its app, but the reply never arrives.
    private void stubLostReply(List after) {
        boolean installed = false
        hubGet.register('/bundle/list/json') { params -> listJson(installed ? after : []) }
        hubGet.register('/hub2/userAppTypes') { params -> JsonOutput.toJson(installed ? [[id: 50, name: 'Target']] : []) }
        hubGet.register('/hub2/userDeviceTypes') { params -> '[]' }
        hubGet.register('/hub2/userLibraries') { params -> '[]' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> installed = true; throw new RuntimeException('read timed out') }
    }

    def "a lost reply reads the bundle list again and reports the bundle the install added"() {
        given:
        enableWrite()
        modernHub()
        stubLostReply([bundleRow(5)])

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error.contains('read timed out')
        result.bundleId == '5'
        result.bundle.name == 'Throwaway'
        result.codeAdded == [[type: 'app', id: '50', name: 'Target']]
        result.note.contains('appears to have completed')
        result.note.contains('added to Code during the install')
        !result.note.contains('no bundle lists it')
        hubGet.calls*.path.count { it == '/bundle/list/json' } == 2
    }

    def "a lost reply with no new bundle in the list read again points at hub_list_bundles before any delete"() {
        given:
        enableWrite()
        modernHub()
        stubLostReply([])

        when:
        def result = createBundle()

        then:
        result.success == false
        result.bundleId == null
        !result.containsKey('bundle')
        result.codeAdded == [[type: 'app', id: '50', name: 'Target']]
        result.note.contains('shows no new bundle it can tie to this install')
        result.note.contains('Check hub_list_bundles before retrying or deleting anything with hub_delete_item')
        !result.note.contains('no bundle lists it')
        hubGet.calls*.path.count { it == '/bundle/list/json' } == 2
    }

    @Unroll
    def "a lost reply via dispatch is an isError result that carries the bundle found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        stubLostReply([bundleRow(5)])

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.jsonrpc == "2.0"
        response.id == mcpDriver.lastSentId
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.bundleId == '5'
        inner.note.contains('appears to have completed')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_install_bundle via dispatch refuses a bad #arg before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true] + [(arg): value])

        then:
        response.jsonrpc == "2.0"
        response.id == mcpDriver.lastSentId
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains(message)
        hubGet.calls.isEmpty()

        where:
        useGateways | arg         | value              | message
        true        | 'importUrl' | '   '              | 'importUrl is required'
        false       | 'importUrl' | '   '              | 'importUrl is required'
        true        | 'importUrl' | 'ftp://host/x.zip' | 'scheme must be http or https'
        false       | 'importUrl' | 'ftp://host/x.zip' | 'scheme must be http or https'
        true        | 'installer' | 'yes'              | 'installer must be true or false'
        false       | 'installer' | 'yes'              | 'installer must be true or false'
    }

    // -------- the shared helper --------

    def "_installBundleFromUrl never throws and reports the endpoint it used"() {
        given:
        modernHub()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> throw new RuntimeException('boom') }

        when:
        def r = script._installBundleFromUrl(BUNDLE_URL, false)

        then:
        r.ok == false
        r.endpoint == '/bundle2/uploadZipFromUrl'
        r.error.contains('boom')
    }

    @Unroll
    def "_bundleResponseSucceeded(#input) == #expected"() {
        expect:
        script._bundleResponseSucceeded(input) == expected

        where:
        input                       | expected
        null                        | false
        [success: true]             | true
        [success: 'true']           | true
        [success: false]            | false
        [foo: 'bar']                | false
        '{"success":true}'          | true
        '{"success":"true"}'        | true
        '{"success":false}'         | false
        'true'                      | true
        'TRUE'                      | true
        'false'                     | false
        ''                          | false
        '   '                       | false
        'not json at all'           | false
    }

    @Unroll
    def "_firmwareAtLeast(#fw, 2.3.8.108) == #expected"() {
        expect:
        script._firmwareAtLeast(fw, '2.3.8.108') == expected

        where:
        fw           | expected
        '2.3.8.108'  | true   // equal -> >= holds
        '2.3.8.109'  | true
        '2.3.8.107'  | false
        '2.3.9.0'    | true
        '2.3.7.140'  | false
        '2.4.0.0'    | true
        '3.0.0.0'    | true
        '2.3.10.0'   | true   // numeric: 10 > 8 (a lexical compare would wrongly say false)
        'abc'        | false  // non-numeric segment scores 0 -> compares BELOW any real target
        'v2.5.0'     | false  // ...including a 'v' prefix: blank is the only assume-modern case
        '2.3.8'      | false  // shorter -> trailing segment treated as 0, 0 < 108
        null         | true   // unreadable -> assume modern
        ''           | true
    }
}
