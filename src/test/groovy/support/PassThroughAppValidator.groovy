package support

import groovy.lang.GroovyShell
import me.biocomp.hubitat_ci.app.AppValidator
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.util.DoNotCallMeBinding
import me.biocomp.hubitat_ci.validation.Flags
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.CompilationCustomizer

/**
 * AppValidator subclass that swaps in {@link PassThroughSandboxClassLoader}
 * so sandbox-loaded scripts can reference our literal `hubitat.helper.*`
 * stubs without HubitatCI's `mapClassName` remap rejecting the load via
 * the JVM's §5.3.5 name-equality check.
 *
 * ## CRITICAL: must be used via `sandbox.run(...)`, NOT `sandbox.compile(...)`
 *
 * `HubitatAppSandbox.compile(Map)` calls
 * `addFlags(options, [Flags.DontRunScript])` BEFORE delegating to
 * `setupImpl`. That mutates `options.validationFlags` to a non-empty
 * list. `setupImpl → readValidator` then takes its first branch:
 *
 *     if (options.validationFlags) {
 *         return new AppValidator(options.validationFlags as List)
 *     }
 *     else if (options.validator) {
 *         return options.validator as AppValidator
 *     }
 *
 * — silently constructing a fresh default `AppValidator` and discarding
 * the user-supplied `validator:` option. This is effectively a HubitatCI
 * bug: the upstream test suite never combines `compile()` with
 * `validator:`, so the precedence trap goes unnoticed.
 *
 * `sandbox.run()` does NOT call `addFlags`, so passing `validator:` to
 * it works as expected. Include `Flags.DontRunScript` in this
 * validator's own constructor flags so `setupImpl`'s
 * `hasFlag(DontRunScript)` check still skips the auto-`script.run()`
 * call:
 *
 *     def validator = new PassThroughAppValidator([
 *         Flags.DontValidatePreferences,
 *         Flags.DontValidateDefinition,
 *         Flags.DontRestrictGroovy,
 *         Flags.DontRunScript    // replaces what compile() used to add
 *     ])
 *     def script = sandbox.run(api: appExecutor,
 *                              userSettingValues: settingsMap,
 *                              validator: validator)
 *
 * ## constructParser override caveats
 *
 * `AppValidator` and `ValidatorBase` are `@TypeChecked`, but
 * `@TypeChecked` alone does NOT force static dispatch (only
 * `@CompileStatic` does). Subclass overrides of `parseScript` and
 * `constructParser` dispatch normally at runtime.
 *
 * `constructParser` here is a minimal version that skips
 * `ValidatorBase`'s private `restrictScript` /
 * `makePrivatePublic` / `validateAfterEachMethod` /
 * `addExtraCustomizers` (those are private and not accessible to a
 * subclass). For our use (helper-class resolution + the RM-tool specs
 * PR #79 needs) the simpler parser is fine; specs that depend on those
 * validation behaviours should use the default `AppValidator` instead.
 */
class PassThroughAppValidator extends AppValidator {
    PassThroughAppValidator(List<Flags> validationFlags = []) {
        super(validationFlags ? EnumSet.copyOf(validationFlags) : EnumSet.noneOf(Flags))
    }

    @Override
    HubitatAppScript parseScript(File scriptFile) {
        def scriptFileText = scriptFile.getText('UTF-8')
        def name = scriptFile.name
        def dot = name.lastIndexOf('.')
        def scriptName = dot > 0 ? name.substring(0, dot) : name
        return parseScript(scriptFileText, scriptName)
    }

    @Override
    HubitatAppScript parseScript(String scriptText, String scriptName = "Script1") {
        scriptText = patchScriptText(scriptText)
        return constructParser(HubitatAppScript).parse(scriptText, scriptName) as HubitatAppScript
    }

    @Override
    protected GroovyShell constructParser(Class c, List<CompilationCustomizer> extraCompilationCustomizers = []) {
        def cc = new CompilerConfiguration()
        cc.scriptBaseClass = c.name
        if (extraCompilationCustomizers) {
            cc.addCompilationCustomizers(*(extraCompilationCustomizers as CompilationCustomizer[]))
        }
        return new GroovyShell(
            new PassThroughSandboxClassLoader(c.classLoader),
            new DoNotCallMeBinding(),
            cc
        )
    }
}
