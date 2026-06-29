package server

import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase
import support.TestHub
import support.TestLocation

/**
 * Spec for hub_install_bundle (toolInstallBundle) -- the HPM-style bundle installer
 * in the hub_manage_code gateway. It tells the hub to fetch + unpack a bundle .zip
 * exactly the way Hubitat Package Manager does, so a package's library/app/driver
 * delivery can be proven server-side without driving the HPM UI.
 *
 * Hub API contracts verified here:
 *   GET  /bundle2/uploadZipFromUrl?url=&pwd=&private=  -- firmware >= 2.3.8.108; raw text body
 *   POST /bundle/uploadZipFromUrl  (JSON {url, installer, pwd}) -- older firmware
 *   success signalled by {"success":true} on either endpoint.
 *
 * Mocking strategy mirrors ToolLibraryCodeSpec:
 *   - hubInternalGet      -> routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPostJson -> stubbed per-test on script.metaClass.
 *   - location.hub        -> appExecutor.getLocation() returns sharedLocation; tests set
 *                            sharedLocation.hub to a TestHub with the firmware under test.
 *
 * Each behavioural feature has a parallel "via dispatch" feature firing the same tool
 * through mcpDriver.callTool so the production envelope path is covered too.
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

    // -------- gating: Write master + destructive confirm --------

    def "hub_install_bundle throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @Unroll
    def "hub_install_bundle via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_install_bundle throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: BUNDLE_URL])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @Unroll
    def "hub_install_bundle via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    // -------- argument validation --------

    def "hub_install_bundle throws when importUrl is missing"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('importUrl is required')
    }

    def "hub_install_bundle throws when importUrl is blank"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: '   ', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('importUrl is required')
    }

    @Unroll
    def "hub_install_bundle throws on non-http(s) importUrl scheme: #badUrl"() {
        given:
        enableWrite()

        when:
        script.toolInstallBundle([importUrl: badUrl, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('scheme must be http or https')

        where:
        badUrl << ['ftp://host/x.zip', 'file:///etc/passwd', 'mcp-libraries.zip']
    }

    @Unroll
    def "hub_install_bundle via dispatch returns -32602 envelope when importUrl missing/blank (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('importUrl is required')

        where:
        useGateways << [true, false]
    }

    // -------- modern firmware: GET /bundle2/uploadZipFromUrl --------

    def "hub_install_bundle (modern firmware) GETs /bundle2/uploadZipFromUrl with url+pwd+private and returns success"() {
        given:
        enableWrite()
        modernHub()
        Map captured = null
        hubGet.register('/bundle2/uploadZipFromUrl') { params ->
            captured = params
            '{"success":true}'
        }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, confirm: true])

        then: 'query carries the url, an empty pwd, and private=false (installer defaults off)'
        captured.url == BUNDLE_URL
        captured.pwd == ''
        captured['private'] == 'false'

        and:
        result.success == true
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.installer == false
        result.message.contains('Bundle installed')
        result.lastBackup != null
    }

    def "hub_install_bundle (modern firmware) marks private=true when installer=true"() {
        given:
        enableWrite()
        modernHub()
        Map captured = null
        hubGet.register('/bundle2/uploadZipFromUrl') { params ->
            captured = params
            '{"success":true}'
        }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, installer: true, confirm: true])

        then:
        captured['private'] == 'true'
        result.success == true
        result.installer == true
    }

    def "hub_install_bundle trims surrounding whitespace from importUrl before sending"() {
        given:
        enableWrite()
        modernHub()
        Map captured = null
        hubGet.register('/bundle2/uploadZipFromUrl') { params ->
            captured = params
            '{"success":true}'
        }

        when:
        def result = script.toolInstallBundle([importUrl: "  ${BUNDLE_URL}  ".toString(), confirm: true])

        then:
        captured.url == BUNDLE_URL
        result.success == true
    }

    def "hub_install_bundle defaults to the modern endpoint when firmware version is unreadable"() {
        given:
        enableWrite()
        sharedLocation.hub = new TestHub()  // firmwareVersionString null -> assume modern
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":true}' }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, confirm: true])

        then:
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.success == true
    }

    // -------- old firmware: POST /bundle/uploadZipFromUrl --------

    def "hub_install_bundle (old firmware) POSTs JSON to /bundle/uploadZipFromUrl"() {
        given:
        enableWrite()
        oldHub()
        String capturedPath = null
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedPath = path
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
        }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, installer: true, confirm: true])

        then:
        capturedPath == '/bundle/uploadZipFromUrl'
        capturedBody.url == BUNDLE_URL
        capturedBody.installer == true
        capturedBody.pwd == ''

        and:
        result.success == true
        result.endpoint == '/bundle/uploadZipFromUrl'
        result.installer == true
    }

    // -------- failure handling --------

    def "hub_install_bundle returns success=false when the hub reports no success signal"() {
        given:
        enableWrite()
        modernHub()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false,"message":"bad zip"}' }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, confirm: true])

        then:
        result.success == false
        result.error.contains('no success signal')
        result.rawResponse.contains('bad zip')
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.lastBackup != null
    }

    def "hub_install_bundle returns success=false when the hub call throws"() {
        given:
        enableWrite()
        modernHub()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> throw new RuntimeException('connection refused') }

        when:
        def result = script.toolInstallBundle([importUrl: BUNDLE_URL, confirm: true])

        then:
        result.success == false
        result.error.contains('connection refused')
        result.endpoint == '/bundle2/uploadZipFromUrl'
        result.lastBackup != null
    }

    @Unroll
    def "hub_install_bundle via dispatch installs the bundle and returns success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":true}' }

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.endpoint == '/bundle2/uploadZipFromUrl'
        inner.message.contains('Bundle installed')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_install_bundle via dispatch returns success=false envelope when hub reports no success signal (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        modernHub()
        hubGet.register('/bundle2/uploadZipFromUrl') { params -> '{"success":false}' }

        when:
        def response = mcpDriver.callTool('hub_install_bundle', [importUrl: BUNDLE_URL, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('no success signal')

        where:
        useGateways << [true, false]
    }

    // -------- _bundleResponseSucceeded normalization --------

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

    // -------- _firmwareAtLeast numeric (not lexical) comparison --------

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
        '2.3.8'      | false  // shorter -> trailing segment treated as 0, 0 < 108
        null         | true   // unreadable -> assume modern
        ''           | true
    }
}
