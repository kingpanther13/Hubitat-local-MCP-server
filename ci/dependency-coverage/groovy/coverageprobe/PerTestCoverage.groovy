package coverageprobe

import groovy.json.JsonOutput
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.model.SpecInfo

/** Snapshot each iteration, including its setup/cleanup, without altering assertions. */
class PerTestCoverage implements IGlobalExtension {
    private static final Object LOCK = new Object()
    private static int sequence = 0
    private static final File output = new File(System.getProperty('coverage.output'))
    private static final def agent = Class.forName('org.jacoco.agent.rt.RT').getMethod('getAgent').invoke(null)

    void visitSpec(SpecInfo spec) {
        spec.allFeatures.each { feature ->
            feature.addIterationInterceptor({ invocation ->
                synchronized (LOCK) {
                    int number = sequence++
                    def snapshots = new File(output, 'iterations')
                    snapshots.mkdirs()
                    // Preserve setupSpec/data-provider execution in the suite aggregate.
                    new File(snapshots, "${number}-outside.exec").bytes = agent.getExecutionData(true)
                    try {
                        invocation.proceed()
                    } finally {
                        new File(snapshots, "${number}.exec").bytes = agent.getExecutionData(true)
                        new File(snapshots, "${number}.json").text = JsonOutput.toJson([
                            spec: invocation.spec.reflection.name, feature: invocation.feature.name,
                            iteration: invocation.iteration.iterationIndex,
                            display: invocation.iteration.displayName, file: "${number}.exec"])
                    }
                }
            } as IMethodInterceptor)
        }
    }
}
