package rules

/**
 * Lane self-test (issue #230). Proves a `parent` assigned in a given: block is
 * visible as `script.parent` through joelwetzel's `@Delegate` to
 * AppExecutor.getParent() under Groovy 2.5.
 *
 * The root (eighty20results) harness can't intercept `parent` via the mock — it
 * propagates through `script.setParent()` reflection because eighty20results'
 * HubitatAppScript overrides getProperty("parent"). This lane relies on the
 * opposite (biocomp routes the property through `@Delegate`, so the getParent()
 * stub is enough). That is the single load-bearing behavioural difference of the
 * rule lane, so assert it directly: a wrong assumption would otherwise let
 * `parent == null` branches pass silently.
 */
class RuleParentWiringSpec extends RuleHarnessSpec {

    def "parent assigned in given: is visible as script.parent via the AppExecutor @Delegate"() {
        given:
        def stubParent = new Object()
        parent = stubParent

        expect:
        script.getParent().is(stubParent)
    }

    def "parent defaults to null on entry to each feature"() {
        expect:
        script.getParent() == null
    }
}
