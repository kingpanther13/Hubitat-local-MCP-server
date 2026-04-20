package rules

/**
 * Spec for hubitat-mcp-rule.groovy::evaluateComparison.
 *
 * Pure function — takes a current value, an operator, and a target value,
 * returns a boolean. No device/parent/state interaction. Covers every
 * operator branch plus the null-safe and numeric-conversion-failure paths.
 */
class EvaluateComparisonSpec extends RuleHarnessSpec {

    def "equals returns true when values are stringly equal"() {
        expect:
        script.evaluateComparison('on', 'equals', 'on') == true
        script.evaluateComparison(42, 'equals', '42') == true
        script.evaluateComparison(42, '==', '42') == true
    }

    def "equals returns false when values differ"() {
        expect:
        script.evaluateComparison('on', 'equals', 'off') == false
        script.evaluateComparison(42, '==', '43') == false
    }

    def "not_equals is the inverse of equals"() {
        expect:
        script.evaluateComparison('on', 'not_equals', 'off') == true
        script.evaluateComparison('on', '!=', 'on') == false
    }

    def "numeric operators compare via BigDecimal"() {
        expect:
        script.evaluateComparison(10, '>', 5) == true
        script.evaluateComparison(5, '>', 10) == false
        script.evaluateComparison(10, '<', 5) == false
        script.evaluateComparison(5, '<', 10) == true
        script.evaluateComparison(10, '>=', 10) == true
        script.evaluateComparison(10, '<=', 10) == true
        script.evaluateComparison(9, '>=', 10) == false
        script.evaluateComparison(11, '<=', 10) == false
    }

    def "numeric operators accept string inputs"() {
        expect: 'current and target arrive as Strings from attribute reads'
        script.evaluateComparison('10', '>', '5') == true
        script.evaluateComparison('5.5', '>=', '5.5') == true
    }

    def "numeric operators fall back to string equality when conversion fails"() {
        expect: 'non-numeric values like "on" can\'t BigDecimal-convert; fallback is str ==='
        script.evaluateComparison('on', '>', 'off') == false
        script.evaluateComparison('on', '>', 'on') == true
    }

    def "null current with equals target null is true"() {
        expect:
        script.evaluateComparison(null, 'equals', null) == true
        script.evaluateComparison(null, '==', 'null') == true
    }

    def "null current with equals non-null target is false"() {
        expect:
        script.evaluateComparison(null, 'equals', 'on') == false
        script.evaluateComparison(null, '==', '42') == false
    }

    def "null current with not_equals non-null target is true"() {
        expect:
        script.evaluateComparison(null, 'not_equals', 'on') == true
        script.evaluateComparison(null, '!=', '42') == true
    }

    def "null current with numeric operator is false (fail closed)"() {
        expect: 'numeric comparisons with a null current value are always false'
        script.evaluateComparison(null, '>', 5) == false
        script.evaluateComparison(null, '<', 5) == false
        script.evaluateComparison(null, '>=', 5) == false
        script.evaluateComparison(null, '<=', 5) == false
    }

    def "unknown operator falls back to string equality"() {
        expect:
        script.evaluateComparison('on', 'like', 'on') == true
        script.evaluateComparison('on', 'unknown', 'off') == false
    }

    def "numeric operator with null target falls through to string equality (caught path)"() {
        // target?.toBigDecimal() returns null; comparing BigDecimal with null
        // throws inside the try, so the catch falls back to
        // current.toString() == target?.toString() — '10' == 'null' is false.
        // Pinning this so a future "throw on null target" change is caught.
        expect:
        script.evaluateComparison(10, '>', null) == false
        script.evaluateComparison(10, '<', null) == false
        script.evaluateComparison(10, '>=', null) == false
        script.evaluateComparison(10, '<=', null) == false
    }

    def "equals operator with null target on non-null current is false"() {
        expect: 'distinct from the null-current branch — current present, target absent'
        script.evaluateComparison(10, 'equals', null) == false
        script.evaluateComparison('on', '==', null) == false
    }
}
