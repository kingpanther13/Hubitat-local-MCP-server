package support

/**
 * install() mutates the global metaclass of hubitat.helper.NetworkUtils —
 * see RMUtilsMock for the same constraint. maxParallelForks = 1 in
 * build.gradle keeps specs sequential so these static overrides don't race.
 */
class NetworkUtilsMock {
    final List<Map> calls = []
    final List<Map> pingCalls = []
    Map stubResponse = [status: 200, body: '{}']
    /** ipAddress -> Map (PingData fields) OR Closure(host, count) -> Map. Throws if value is a Throwable. */
    Map<String, Object> pingResponses = [:]
    /** Default response when no per-host override matches. Set to null to throw a "no stub" error. */
    Map defaultPingResponse = [packetsTransmitted: 3, packetsReceived: 0, packetLoss: 100, rttAvg: 0.0, rttMin: 0.0, rttMax: 0.0]

    Object sendHubitatCommand(Map params) {
        calls << params
        return stubResponse
    }

    Object ping(String ipAddress, Integer count) {
        pingCalls << [ipAddress: ipAddress, count: count]
        def stub = pingResponses[ipAddress]
        if (stub instanceof Throwable) throw stub
        if (stub instanceof Closure) return stub.call(ipAddress, count)
        if (stub != null) return stub
        return defaultPingResponse
    }

    void install() {
        def self = this
        hubitat.helper.NetworkUtils.metaClass.static.sendHubitatCommand = { Map p ->
            self.sendHubitatCommand(p)
        }
        hubitat.helper.NetworkUtils.metaClass.static.ping = { String ip, Integer n ->
            self.ping(ip, n)
        }
    }

    void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(hubitat.helper.NetworkUtils)
    }
}
