package support

/**
 * install() mutates the global metaclass of hubitat.helper.NetworkUtils —
 * see RMUtilsMock for the same constraint. maxParallelForks = 1 in
 * build.gradle keeps specs sequential so these static overrides don't race.
 */
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
