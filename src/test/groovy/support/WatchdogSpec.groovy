package support

import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import spock.lang.Specification

abstract class WatchdogSpec extends Specification {
    private static final String SOURCE = new File('e2e-deadman-watchdog-v3-1.groovy').getText('UTF-8')

    def setup() {
        // Specs run sequentially; a cached class must start each feature like a fresh code load.
        WatchdogAppValidator.resetCachedScripts()
    }

    protected HubitatAppScript loadWatchdog(Map options) {
        new HubitatAppSandbox(SOURCE).run(options + [validator: new WatchdogAppValidator()])
    }
}
