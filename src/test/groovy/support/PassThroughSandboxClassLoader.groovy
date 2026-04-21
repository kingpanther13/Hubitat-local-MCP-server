package support

import me.biocomp.hubitat_ci.util.SandboxClassLoader

/**
 * SandboxClassLoader subclass that bypasses HubitatCI's `mapClassName`
 * remap for a curated set of Hubitat platform helper classes — currently
 * `hubitat.helper.RMUtils` and `hubitat.helper.NetworkUtils`.
 *
 * Why this exists: eighty20results' `SandboxClassLoader.loadClass(name)`
 * calls `super.loadClass(mapClassName(name))`. For `hubitat.helper.RMUtils`
 * the fork's `mapClassName` rewrites to
 * `me.biocomp.hubitat_ci.api.common_api.RMUtils` and the fork ships that
 * class — so the classloader returns it fine. But the JVM's class
 * resolution step (JVMS §5.3.5) enforces that the class returned for a
 * named reference has that exact name. Since sandbox-loaded script
 * bytecode carries constant-pool entries for the literal
 * `hubitat/helper/RMUtils`, linkage rejects the remapped class with
 * `NoClassDefFoundError: hubitat/helper/RMUtils`.
 *
 * The remap is still appropriate for HubitatCI's own renamed-API classes
 * (Location, Hub, etc.). For platform helpers we stub literally at
 * `src/main/groovy/hubitat/helper/`, we need the lookup to skip the
 * remap and resolve the literal name from the parent classloader.
 *
 * Used via `support.PassThroughAppValidator`, which wires this loader
 * into HubitatCI's compile path; see that class's Javadoc for the
 * `sandbox.run()` vs `sandbox.compile()` precedence trap that must be
 * observed.
 */
class PassThroughSandboxClassLoader extends SandboxClassLoader {
    private static final Set<String> PASSTHROUGH_NAMES = [
        'hubitat.helper.RMUtils',
        'hubitat.helper.NetworkUtils'
    ] as Set

    PassThroughSandboxClassLoader(ClassLoader parent) {
        super(parent)
    }

    @Override
    Class<?> loadClass(String name, boolean resolve) {
        if (PASSTHROUGH_NAMES.contains(name)) {
            Class<?> c = findLoadedClass(name)
            if (c == null) {
                c = getParent().loadClass(name)
            }
            if (resolve) {
                resolveClass(c)
            }
            return c
        }
        return super.loadClass(name, resolve)
    }
}
