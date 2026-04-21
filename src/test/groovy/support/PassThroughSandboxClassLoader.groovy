package support

import me.biocomp.hubitat_ci.util.SandboxClassLoader

/**
 * SandboxClassLoader subclass that bypasses HubitatCI's `mapClassName`
 * remap for a curated set of platform helper classes — currently
 * `hubitat.helper.RMUtils` and `hubitat.helper.NetworkUtils`.
 *
 * Why this exists: SandboxClassLoader's default `mapClassName`
 * remaps any `hubitat.<x>.<Y>` reference from sandbox-loaded code
 * into `me.biocomp.hubitat_ci.api.<x>.<Y>`. When the class found at
 * the mapped path is named with the mapped FQN (rather than the
 * original `hubitat.<x>.<Y>`), the JVM rejects the load with a
 * name-mismatch (CNFE for the original name) — verified empirically
 * in PR #99 iteration 3 diagnostics.
 *
 * The remap is appropriate for HubitatCI's HubitatAppScript-API
 * classes (Location, Hub, etc.) which are intentionally renamed
 * into `me.biocomp.hubitat_ci.api.*`. For platform helper classes
 * like `hubitat.helper.RMUtils` that we stub literally at
 * `src/main/groovy/hubitat/helper/RMUtils.groovy`, we need the
 * lookup to skip the remap and resolve the literal name.
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
