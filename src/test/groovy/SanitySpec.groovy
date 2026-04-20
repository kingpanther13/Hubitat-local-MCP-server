import spock.lang.Specification

class SanitySpec extends Specification {
    def "toolchain works"() {
        expect:
        1 + 1 == 2
    }
}
