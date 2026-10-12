package support

import me.biocomp.hubitat_ci.validation.Flags

class WatchdogAppValidator extends PassThroughAppValidator {
    WatchdogAppValidator() {
        super([Flags.DontValidatePreferences, Flags.DontValidateDefinition,
               Flags.DontRestrictGroovy, Flags.DontRunScript])
    }
}
