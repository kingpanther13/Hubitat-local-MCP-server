package support

class NetworkUtilsMock {
    final List<Map> calls = []
    Map stubResponse = [status: 200, body: '{}']

    Object sendHubitatCommand(Map params) {
        calls << params
        return stubResponse
    }

    void install() {
        def self = this
        hubitat.helper.NetworkUtils.metaClass.static.sendHubitatCommand = { Map p ->
            self.sendHubitatCommand(p)
        }
    }

    void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(hubitat.helper.NetworkUtils)
    }
}
