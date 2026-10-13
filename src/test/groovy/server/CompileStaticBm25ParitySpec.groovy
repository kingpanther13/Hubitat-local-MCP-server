package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.HarnessSpec

class CompileStaticBm25ParitySpec extends HarnessSpec {
    @Shared private Object originalScore

    def setupSpec() {
        originalScore = new GroovyClassLoader().parseClass(
            new File('experiments/compile-static/bm25-v002.groovy')).newInstance()
    }

    private static List bits(List scores) {
        scores.collect { Double.doubleToLongBits((it as Number).doubleValue()) }
    }

    @Unroll
    def "BM25 typing preserves exact score bits for #label"() {
        expect:
        bits(script.bm25Score(documents, query)) == bits(originalScore.bm25Score(documents, query))

        where:
        label          | documents                                                  | query
        'no documents' | []                                                         | ['switch']
        'empty docs'   | [[], [], []]                                               | ['switch']
        'no query'     | [['switch'], ['motion']]                                   | []
        'missing term' | [['switch'], ['motion']]                                   | ['absent']
        'repetitions'  | [['switch', 'switch', 'switch'], ['switch'], ['motion']]   | ['switch', 'switch']
        'mixed sizes'  | [[], ['a'], ['a', 'b', 'b'], ['a', 'a', 'b', 'c', 'd']]    | ['a', 'b', 'd']
        'reserved keys'| [['class', 'size'], ['metaClass', 'class'], ['properties']] | ['class', 'metaClass']
        'long tokens'  | [['x' * 1000, 'switch'], ['switch', 'motion']]               | ['x' * 1000, 'switch']
    }

    def "warmed BM25 JVM timing uses identical checked inputs"() {
        given:
        List documents = (0..<40).collect { doc ->
            (0..<(doc % 12 + 1)).collect { term -> "token_${(doc + term) % 9}".toString() }
        }
        List query = ['token_1', 'token_3', 'token_7']
        List expected = bits(originalScore.bm25Score(documents, query))
        3.times {
            assert bits(originalScore.bm25Score(documents, query)) == expected
            assert bits(script.bm25Score(documents, query)) == expected
        }
        Map samples = [original: [], compiled: []]

        when:
        7.times { sample ->
            List order = sample % 2 ? ['compiled', 'original'] : ['original', 'compiled']
            order.each { name ->
                Object scorer = name == 'original' ? originalScore : script
                long started = System.nanoTime()
                List result = scorer.bm25Score(documents, query)
                long elapsed = System.nanoTime() - started
                assert bits(result) == expected
                samples[name] << elapsed / 1000000.0d
            }
        }
        println JsonOutput.toJson([measurement: 'warmed JVM helper only; not a hub timing guarantee',
                                   originalMedianMs: samples.original.sort()[3],
                                   compiledMedianMs: samples.compiled.sort()[3],
                                   originalSamplesMs: samples.original, compiledSamplesMs: samples.compiled])

        then:
        samples.original.size() == 7
        samples.compiled.size() == 7
    }
}
