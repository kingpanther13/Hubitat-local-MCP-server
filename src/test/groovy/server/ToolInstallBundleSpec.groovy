package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase
import support.TestHub
import support.TestLocation

/**
 * Spec for hub_install_bundle (toolInstallBundle) -- the HPM-style bundle install in the
 * hub_manage_code gateway -- and the _installBundleFromUrl helper it shares with
 * hub_update_package's bundle leg.
 *
 * Hub API contracts verified here:
 *   GET  /hub2/userBundles                              -- read before and after the install
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

    private static Map bundleRow(int id, String name = 'Throwaway') {
        [id: id, name: name, namespace: 'mcptest', 'private': false, content: 'apps [Target], drivers [], libraries []']
    }

    // The list answers `before` until the install runs, then `after`.
    private void stubBundleLists(List before, List after) {
        boolean installed = false
        hubGet.register('/hub2/userBundles') { params -> JsonOutput.toJson(installed ? after : before) }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> installed = true; '{"success":true,"message":null}' }
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
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.installer == false
        result.note == null
        hubGet.calls*.path == ['/hub2/userBundles', '/bundle2/uploadZipFromUrl', '/hub2/userBundles']
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

    def "a reinstall that keeps its id still succeeds, with a note and no id"() {
        given:
        enableWrite()
        modernHub()
        stubBundleLists([bundleRow(5)], [bundleRow(5)])

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('No single new bundle id appeared')
        result.note.contains('hub_list_bundles')
    }

    def "an unreadable list before the install still installs, skips the second read, and reports the id unknown"() {
        given:
        enableWrite()
        modernHub()
        hubGet.register('/hub2/userBundles') { params -> '<html>login</html>' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":true}' }

        when:
        def result = createBundle()

        then:
        result.success == true
        result.bundleId == null
        result.note.contains('could not be read before the install')
        hubGet.calls*.path == ['/hub2/userBundles', '/bundle2/uploadZipFromUrl']
    }

    def "an unreadable list after the install keeps the success"() {
        given:
        enableWrite()
        modernHub()
        int reads = 0
        hubGet.register('/hub2/userBundles') { params -> reads++ == 0 ? '[]' : { throw new RuntimeException('timeout') }() }
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
        hubGet.register('/hub2/userBundles') { params -> '[]' }
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
        hubGet.register('/hub2/userBundles') { params -> '[]' }
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
        hubGet.register('/hub2/userBundles') { params -> '[]' }
        // The hub's reply for a zip it cannot fetch, as captured live.
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false,"message":"Cannot retrieve zip file from https://example.com/missing.zip"}' }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error == 'Bundle install failed: Cannot retrieve zip file from https://example.com/missing.zip'
        result.rawResponse.contains('Cannot retrieve zip file')
        result.endpoint == '/bundle2/uploadZipFromUrl'
        hubGet.calls*.path == ['/hub2/userBundles', '/bundle2/uploadZipFromUrl']
    }

    @Unroll
    def "a failed install via dispatch is a success=false result, not a protocol error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        hubGet.register('/hub2/userBundles') { params -> '[]' }
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
        hubGet.register('/hub2/userBundles') { params -> '[]' }
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> throw new RuntimeException('connection refused') }

        when:
        def result = createBundle()

        then:
        result.success == false
        result.error.contains('connection refused')
        result.note.contains('unknown')
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
