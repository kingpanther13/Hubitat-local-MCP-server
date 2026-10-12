package support

import java.lang.reflect.Modifier
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.util.DoNotCallMeBinding
import me.biocomp.hubitat_ci.validation.Flags

class WatchdogAppValidator extends PassThroughAppValidator {
    private static final Map<List<String>, Class> CLASSES = [:]
    private static final Map<Class, Map<String, Map>> INITIAL_MAPS = [:]

    WatchdogAppValidator() {
        super([Flags.DontValidatePreferences, Flags.DontValidateDefinition,
               Flags.DontRestrictGroovy, Flags.DontRunScript])
    }

    @Override
    HubitatAppScript parseScript(String source, String name = 'Script1') {
        synchronized (CLASSES) {
            def key = [name, source]
            Class type = CLASSES[key]
            if (type == null) {
                type = super.parseScript(source, name).getClass()
                Map initialMaps = [:]
                type.declaredFields.findAll {
                    !it.synthetic && Modifier.isStatic(it.modifiers) && Map.isAssignableFrom(it.type)
                }.each { field ->
                    field.accessible = true
                    initialMaps[field.name] = new LinkedHashMap((Map) field.get(null))
                }
                INITIAL_MAPS[type] = initialMaps
                CLASSES[key] = type
            }
            // Cache bytecode only: HubitatCI still wires fresh readers, settings and API mocks.
            def script = type.getDeclaredConstructor().newInstance() as HubitatAppScript
            script.setBinding(new DoNotCallMeBinding())
            return script
        }
    }

    static void resetCachedScripts() {
        synchronized (CLASSES) {
            INITIAL_MAPS.each { Class type, Map initialMaps ->
                GroovySystem.metaClassRegistry.removeMetaClass(type)
                initialMaps.each { String name, Map initial ->
                    def field = type.getDeclaredField(name)
                    field.accessible = true
                    Map current = (Map) field.get(null)
                    current.clear()
                    current.putAll(initial)
                }
            }
        }
    }
}
