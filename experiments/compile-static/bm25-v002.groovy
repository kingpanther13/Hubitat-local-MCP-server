// Frozen original implementation from deployed experiment 0.0.2 (1afee754).
class Bm25V002 {
@groovy.transform.CompileStatic
private String _bm25Key(String token) {
    // Keep document, term, and query keys in the same namespace. This prefix
    // predates Map.get/put and preserves the existing token representation.
    return "t_${token}".toString()
}
private bm25Score(List<List<String>> docTokens, List<String> queryTokens) {
    def k1 = 1.5
    def b = 0.75
    def n = docTokens.size()

    if (n == 0) return []

    // Document lengths and average
    def docLengths = docTokens.collect { it.size() }
    def avgDl = docLengths.sum() / (double) n
    if (avgDl == 0) return new double[n] as List

    // Document frequency: how many docs contain each token. Preserve the token
    // namespace while using Map methods to avoid sandbox property resolution.
    def df = [:]
    docTokens.each { tokens ->
        tokens.toSet().each { token ->
            def k = _bm25Key(token)
            df.put(k, (df.get(k) ?: 0) + 1)
        }
    }

    // Score each document
    def scores = new double[n]
    docTokens.eachWithIndex { tokens, docIdx ->
        // Term frequency for this doc
        def tf = [:]
        tokens.each { t -> def k = _bm25Key(t); tf.put(k, (tf.get(k) ?: 0) + 1) }

        def dl = docLengths[docIdx]
        double score = 0.0

        queryTokens.each { rawQt ->
            def qt = _bm25Key(rawQt)
            def termFreq = tf.get(qt) ?: 0
            if (termFreq > 0) {
                def docFreq = df.get(qt) ?: 0
                def idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0)
                def num = termFreq * (k1 + 1)
                def den = termFreq + k1 * (1 - b + b * dl / avgDl)
                score += idf * num / den
            }
        }

        scores[docIdx] = score
    }

    return scores as List
}
}
