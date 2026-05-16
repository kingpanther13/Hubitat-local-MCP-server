package support

import spock.lang.Specification

/**
 * Self-test for the {@code HarnessSpec.checkMetaClassClean} helper that
 * fires from {@code setup()} under {@code -PharnessStrictMetaClass=true}.
 *
 * Without this spec, "the strict-mode flag is wired correctly" rests on
 * the implication that all tests pass under
 * {@code -PharnessStrictMetaClass=true} — but a globally-silent no-op
 * assertion would *also* pass. This spec pins the assertion's failure
 * path so future refactors of the metaClass-wipe machinery don't silently
 * de-fang the strict check.
 */
class StrictMetaClassCheckSpec extends Specification {

    /** Disposable script-like target. ExpandoMetaClass works on any GroovyObject. */
    static class FakeScript {
    }

    def "strict mode OFF (default) — no throw even with a planted leak"() {
        given:
        def fake = new FakeScript()
        fake.metaClass.leaky = { -> 'leak' }
        System.clearProperty('harnessStrictMetaClass')

        when:
        HarnessSpec.checkMetaClassClean(fake, 'StrictMetaClassCheckSpec')

        then:
        noExceptionThrown()
    }

    def "strict mode ON + no leaks — passes silently"() {
        given:
        def fake = new FakeScript()
        System.setProperty('harnessStrictMetaClass', 'true')

        when:
        HarnessSpec.checkMetaClassClean(fake, 'StrictMetaClassCheckSpec')

        then:
        noExceptionThrown()

        cleanup:
        System.clearProperty('harnessStrictMetaClass')
    }

    def "strict mode ON + planted expando method — throws with the method name in the message"() {
        given: 'install a per-instance ExpandoMetaClass and add a method to it (matches the production setup() path where the dual wipe leaves a per-instance EMC and `script.metaClass.foo = closure` then attaches there)'
        def fake = new FakeScript()
        def emc = new ExpandoMetaClass(FakeScript, false, true)
        emc.initialize()
        fake.metaClass = emc
        fake.metaClass.sneakyLeak = { -> 'gotcha' }
        System.setProperty('harnessStrictMetaClass', 'true')

        when:
        HarnessSpec.checkMetaClassClean(fake, 'StrictMetaClassCheckSpec')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('sneakyLeak')
        e.message.contains('expando methods')

        cleanup:
        System.clearProperty('harnessStrictMetaClass')
    }

    def "strict mode ON + planted expando property — throws with the property name in the message"() {
        given:
        def fake = new FakeScript()
        def emc = new ExpandoMetaClass(FakeScript, false, true)
        emc.initialize()
        fake.metaClass = emc
        fake.metaClass.sneakyProp = 'leakedValue'
        System.setProperty('harnessStrictMetaClass', 'true')

        when:
        HarnessSpec.checkMetaClassClean(fake, 'StrictMetaClassCheckSpec')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('sneakyProp')
        e.message.contains('expando properties')

        cleanup:
        System.clearProperty('harnessStrictMetaClass')
    }
}
