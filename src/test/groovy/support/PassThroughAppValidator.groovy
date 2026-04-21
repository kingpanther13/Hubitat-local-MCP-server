package support

import groovy.lang.GroovyShell
import me.biocomp.hubitat_ci.app.AppValidator
import me.biocomp.hubitat_ci.util.DoNotCallMeBinding
import me.biocomp.hubitat_ci.validation.Flags
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.CompilationCustomizer

/**
 * AppValidator subclass that swaps in {@link PassThroughSandboxClassLoader}
 * so sandbox-loaded scripts can reference our literal `hubitat.helper.*`
 * stubs without HubitatCI's `mapClassName` remap rejecting the load
 * with a name mismatch.
 *
 * Use via the documented `validator:` option to
 * `HubitatAppSandbox.compile(...)`:
 *
 *   def validator = new PassThroughAppValidator([
 *       Flags.DontValidatePreferences,
 *       Flags.DontValidateDefinition,
 *       Flags.DontRestrictGroovy
 *   ])
 *   def script = sandbox.compile(api: appExecutor,
 *                                userSettingValues: settingsMap,
 *                                validator: validator)
 *
 * Trade-off: this overrides `constructParser` with a minimal version
 * that skips ValidatorBase's `restrictScript` /
 * `makePrivatePublic` / `validateAfterEachMethod` /
 * `addExtraCustomizers` — those are private and not accessible to a
 * subclass. For our use (helper-class resolution probes + the small
 * set of RM-tool specs PR #79 needs) the simpler parser is fine; for
 * specs that depend on those validation behaviors, use the default
 * AppValidator.
 */
class PassThroughAppValidator extends AppValidator {
    PassThroughAppValidator(List<Flags> validationFlags = []) {
        super(validationFlags as List)
    }

    @Override
    protected GroovyShell constructParser(Class c, List<CompilationCustomizer> extraCompilationCustomizers = []) {
        def cc = new CompilerConfiguration()
        cc.scriptBaseClass = c.name
        return new GroovyShell(
            new PassThroughSandboxClassLoader(c.classLoader),
            new DoNotCallMeBinding(),
            cc
        )
    }
}
