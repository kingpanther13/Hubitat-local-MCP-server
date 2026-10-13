// Remote-only Groovy 2.4 parity and compiled-class reduction fixtures.
import groovy.json.JsonOutput
if (System.getenv('GITHUB_ACTIONS') != 'true') throw new IllegalStateException('GitHub Actions only')
if (GroovySystem.version != '2.4.21') throw new IllegalStateException('Expected pinned Groovy 2.4.21')
if (args.length != 2) throw new IllegalArgumentException('usage: parity.groovy <convert.groovy> <report.json>')
GroovyClassLoader converterLoader = new GroovyClassLoader(this.class.classLoader)
converterLoader.parseClass(new File(args[0]))
Class converter = converterLoader.loadClass('EachLoopConverter')
List reports = []

def verify = { String name, String source, int removals, Closure expected ->
    Map result = converter.transform(source, name + '.groovy')
    assert result.edits.size() == removals : name + ': unexpected edits; ' + result.rejected
    def execute = { String code, String suffix ->
        GroovyClassLoader loader = new GroovyClassLoader(this.class.classLoader)
        Class type = loader.parseClass(code, name + suffix + '.groovy')
        def value = ((Script) type.newInstance()).run()
        int count = loader.loadedClasses.count { it.name.contains('_closure') }
        [value: value, closures: count]
    }
    Map original = execute(source, 'Original'), changed = execute(result.source, 'Changed')
    assert original.value == changed.value : name + ': parity mismatch'
    expected(original.value); expected(changed.value)
    assert original.closures - changed.closures == removals : name + ': wrong class reduction'
    if (removals == 0) assert result.source == source : name + ': boundary changed'
    if (removals > 0) {
        int cursor = 0
        String reconstructed = ''
        result.edits.sort { a, b -> a.start <=> b.start }.each { e ->
            assert source.substring(e.start as int, e.end as int) == e.original
            reconstructed += source.substring(cursor, e.start as int) + e.replacement
            cursor = e.end
        }
        reconstructed += source.substring(cursor)
        assert reconstructed == result.source
    }
    reports << [name: name, transformed: removals, originalClasses: original.closures,
                rewrittenClasses: changed.closures, parity: true, rejected: result.rejected]
}

verify('receiverEffectsAndImplicitReturn', '''
def capture(trace) {
    [(trace.add('receiver-1') ? 1 : -1), (trace.add('receiver-2') ? 8 : -1)].each { value ->
        trace.add('body-' + value)
    }
}
def test() { def trace = []; def result = capture(trace); [trace, result] }
test()
''', 1, { value -> assert value == [['receiver-1', 'receiver-2', 'body-1', 'body-8'], [1, 8]] })

verify('implicitItAndCapturedAssignment', '''
def test() { int total = 0; def seen = []; [1, 2, 3].each { total += it; seen << it }; [total, seen] }
test()
''', 1, { value -> assert value == [6, [1, 2, 3]] })

verify('emptyListTailReturn', '''
def test() { [].each { throw new AssertionError('no iteration') } }
test()
''', 1, { value -> assert value == [] })

verify('singleParameterMapEntryAndOrder', '''
def capture(seen) {
    [z: 3, a: 1, m: 2].each { entry ->
        assert entry instanceof Map.Entry
        seen << entry.key
        entry.setValue(entry.value + 10)
    }
}
def test() { def seen = []; def result = capture(seen); [seen, result] }
test()
''', 1, { value -> assert value == [['z', 'a', 'm'], [z: 13, a: 11, m: 12]] })

verify('implicitMapEntry', '''
def test() { def seen = []; [b: 2, a: 1].each { seen << [it.key, it.value] }; seen }
test()
''', 1, { value -> assert value == [['b', 2], ['a', 1]] })

verify('primitiveArray', '''
def test() { def seen = []; ([1, 2, 3] as int[]).each { seen << it }; seen }
test()
''', 1, { value -> assert value == [1, 2, 3] })

verify('objectArrayNullElements', '''
def test() { def seen = []; (['x', null, 'y'] as String[]).each { seen << it }; seen }
test()
''', 1, { value -> assert value == ['x', null, 'y'] })

verify('safeNullableReceiverEvaluatedOnce', '''
def capture(trace, yes) {
    ((trace.add('receiver') && yes) ? [1, null, 3] : null)?.each { value -> trace << value }
}
def test() { def a = [], b = []; def ra = capture(a, true), rb = capture(b, false); [a, b, ra, rb] }
test()
''', 1, { value -> assert value == [['receiver', 1, null, 3], ['receiver'], [1, null, 3], null] })

verify('returnThroughIfTryFinally', '''
def capture(trace, yes) {
    try {
        if (yes) [first: 1, second: 2]?.each { trace << it.key }
        else [].each { trace << it }
    } finally { trace << 'finally' }
}
def test() { def a = [], b = []; [capture(a, true), capture(b, false), a, b] }
test()
''', 2, { value -> assert value == [[first: 1, second: 2], [], ['first', 'second', 'finally'], ['finally']] })

verify('danglingElseAndScope', '''
def capture(yes) {
    def outer = 'outer'
    if (yes) [1, 2].each { value -> def local = value + 1; assert local > 1 }
    else return 'false-branch'
    outer
}
[capture(true), capture(false)]
''', 1, { value -> assert value == ['outer', 'false-branch'] })

verify('elementMutationAndBodyException', '''
def mutate() { def element = [n: 1]; [element].each { it.n += 1 }; element }
def fail() {
    def seen = []
    try { [1, 2, 3].each { seen << it; if (it == 2) throw new IllegalArgumentException('stop') } }
    catch (IllegalArgumentException problem) { seen << problem.message }
    seen
}
[mutate(), fail()]
''', 2, { value -> assert value == [[n: 2], [1, 2, 'stop']] })

String tricky = '''
def test() {
    def seen = []
    ['{ -> }', 'slash//', '/* block */'].each { value /* -> decoy */ -> seen << "seen:${value}" }
    seen
}
test()
'''.replace('\n', '\r\n')
verify('commentsStringsAndCRLF', tricky, 1, { value -> assert value == ['seen:{ -> }', 'seen:slash//', 'seen:/* block */'] })

verify('closureEarlyReturnBoundary', '''
def test() { def seen = []; [1, 2, 3].each { if (it == 2) return; seen << it }; seen }
test()
''', 0, { value -> assert value == [1, 3] })

verify('nestedClosureBoundary', '''
def test() { def seen = []; [1, 2].each { value -> [value].each { seen << it } }; seen }
test()
''', 0, { value -> assert value == [1, 2] })

verify('typedParameterBoundary', '''
def test() { [1, 2].each { int value -> assert value > 0 } }
test()
''', 0, { value -> assert value == [1, 2] })

verify('twoParameterMapBoundary', '''
def test() { [a: 1].each { key, value -> assert key == 'a' && value == 1 } }
test()
''', 0, { value -> assert value == [a: 1] })

verify('closureIdentityBoundary', '''
def test() { def seen = []; [1].each { assert owner.is(this); assert thisObject.is(this); seen << delegate.is(owner) }; seen }
test()
''', 0, { value -> assert value == [true] })

verify('valueReturnAndChainedBoundary', '''
def test() { def a = [1].each { assert it == 1 }; def b = [2].each { assert it == 2 }.size(); [a, b, direct()] }
def direct() { return [3].each { assert it == 3 } }
test()
''', 0, { value -> assert value == [[1], 1, [3]] })

verify('customDispatchAndCastBoundary', '''
class StrangeList extends ArrayList { Object each(Closure operation) { operation.call(99); 'custom-return' } }
def test() { def receiver = new StrangeList(); def seen = []; (receiver as List).each { seen << it }; [seen, receiver.size()] }
test()
''', 0, { value -> assert value == [[99], 0] })

verify('nullableNonSafeAndUnknownReceiverBoundary', '''
def createUnknown() { [1, 2] }
def test() {
    def seen = [], unknown = createUnknown()
    unknown.each { seen << it }; unknown?.each { seen << it }
    (true ? [3] : null).each { seen << it }; null?.each { seen << it }
    seen
}
test()
''', 0, { value -> assert value == [1, 2, 1, 2, 3] })

verify('rootDslAndAsyncCallbackBoundary', '''
def schedule(Closure callback) { callback.call() }
def test() { def seen = []; schedule { [1].each { seen << it } }; seen }
[7].each { assert it == 7 }
test()
''', 0, { value -> assert value == [1] })

verify('structuralMutationBoundary', '''
def test() {
    def receiver = [1, 2, 3]
    def seen = []
    try { receiver.each { seen << it; receiver << 4 } }
    catch (ConcurrentModificationException expected) { seen << 'fail-fast' }
    seen
}
test()
''', 1, { value -> assert value == [1, 'fail-fast'] })

verify('effectivelyFinalAccumulator', '''
def test() {
    List items = []
    for (def n in [1, 2, 3]) items << n
    def seen = []
    items.each { seen << it }
    [items, seen]
}
test()
''', 1, { value -> assert value == [[1, 2, 3], [1, 2, 3]] })

verify('exactArrayListConstructor', '''
def test() {
    def items = new java.util.ArrayList([3, 1, 2])
    def seen = []
    items.each { seen << it }
    seen
}
test()
''', 1, { value -> assert value == [3, 1, 2] })

verify('exactLinkedHashMapAndAliasedMutation', '''
def test() {
    Map items = new java.util.LinkedHashMap([z: 3, a: 1])
    def alias = items
    alias.put('b', 2)
    def seen = []
    items.each { seen << it.key; it.setValue(it.value + 10) }
    [items, seen, items.is(alias)]
}
test()
''', 1, { value -> assert value == [[z: 13, a: 11, b: 12], ['z', 'a', 'b'], true] })

verify('localReceiverImplicitReturnIdentity', '''
def capture(holder) {
    def items = [1, 2]
    holder << items
    items.each { assert it > 0 }
}
def test() { def holder = []; def result = capture(holder); [result, result.is(holder[0])] }
test()
''', 1, { value -> assert value == [[1, 2], true] })

verify('sameNameIndependentMethods', '''
def changed() {
    def items = [1]
    items = [2]
    def seen = []
    items.each { seen << it }
    seen
}
def stable() {
    def items = [3]
    def seen = []
    items.each { seen << it }
    seen
}
[changed(), stable()]
''', 1, { value -> assert value == [[2], [3]] })

verify('branchLocalDominance', '''
def test(flag) {
    def seen = []
    if (flag) {
        def items = [1]
        items.each { seen << it }
    } else {
        def items = [2]
        items.each { seen << it }
    }
    seen
}
[test(true), test(false)]
''', 2, { value -> assert value == [[1], [2]] })

verify('reassignmentInsideClosureBoundary', '''
def test() {
    def items = [1]
    def later = { items = [9] }
    def seen = []
    items.each { seen << it }
    later()
    [seen, items]
}
test()
''', 0, { value -> assert value == [[1], [9]] })

verify('compoundReassignmentBoundary', '''
def test() {
    def items = [1]
    items += [2]
    def seen = []
    items.each { seen << it }
    seen
}
test()
''', 0, { value -> assert value == [1, 2] })

verify('parameterReceiverBoundary', '''
def capture(items) { def seen = []; items.each { seen << it }; seen }
capture([1, 2])
''', 0, { value -> assert value == [1, 2] })

verify('constructorShadowBoundary', '''
class ArrayList extends java.util.ArrayList {
    Object each(Closure callback) { callback.call(77); this }
}
def test() {
    def items = new ArrayList()
    def seen = []
    items.each { seen << it }
    seen
}
test()
''', 0, { value -> assert value == [77] })

verify('directFreshConstructor', '''
def test() {
    def seen = []
    new java.util.ArrayList([2, 1]).each { seen << it }
    seen
}
test()
''', 1, { value -> assert value == [2, 1] })

verify('collectNullsAndCapturedEffects', '''
def test() {
    def items = [1, 2, 3]
    def trace = []
    def result = items.collect { trace.add(it) ? (it == 2 ? null : it * 2) : -1 }
    [result, trace, result.getClass().name]
}
test()
''', 1, { value -> assert value == [[2, null, 6], [1, 2, 3], 'java.util.ArrayList'] })

verify('findAllGroovyTruthAndElementIdentity', '''
def test() {
    def items = [[n: 1], [n: 2], [n: 3]]
    def result = items.findAll { (it.n++ % 2) }
    [result, items, result[0].is(items[0]), result[1].is(items[2]), result.getClass().name]
}
test()
''', 1, { value -> assert value == [[[n: 2], [n: 4]], [[n: 2], [n: 3], [n: 4]], true, true, 'java.util.ArrayList'] })

verify('collectDeclarationTailReturn', '''
def test() { List result = [1, 2].collect { it * 2 } }
test()
''', 1, { value -> assert value == [2, 4] })

verify('collectAndFindAllStatementTailReturn', '''
def first() { [1, 2].collect { it * 2 } }
def second() { [1, 2, 3].findAll { it % 2 } }
def empty() { [].collect { it } }
[first(), second(), empty()]
''', 3, { value -> assert value == [[2, 4], [1, 3], []] })

verify('nullableLocalCollectionPipeline', '''
def capture(flag, seen) {
    def items = flag ? [1, 2] : null
    def result = items?.collect { it * 2 }
    result?.each { seen << it }
}
def test() { def a = [], b = []; [capture(true, a), capture(false, b), a, b] }
test()
''', 2, { value -> assert value == [[2, 4], null, [2, 4], []] })

verify('provenProducerPipeline', '''
def test() {
    def source = [1, 2, 3]
    def doubled = source.collect { it * 2 }
    def chosen = doubled.findAll { it > 2 }
    def seen = []
    chosen.each { seen << it }
    [chosen, seen]
}
test()
''', 3, { value -> assert value == [[4, 6], [4, 6]] })

verify('unconvertedComplexProducerStillFresh', '''
def test() {
    def source = [1, 2]
    def result = source.collect { return it * 2 }
    def seen = []
    result.each { seen << it }
    seen
}
test()
''', 1, { value -> assert value == [2, 4] })

verify('collectExceptionOrder', '''
def element(value, trace) {
    trace << value
    if (value == 2) throw new IllegalArgumentException('stop')
    value * 2
}
def test() {
    def trace = []
    try { def result = [1, 2, 3].collect { element(it, trace) } }
    catch (IllegalArgumentException expected) { trace << expected.message }
    trace
}
test()
''', 1, { value -> assert value == [1, 2, 'stop'] })

verify('unsupportedCollectionContexts', '''
def test() {
    def list = [1, 2]
    def a = list.collect { int n -> n * 2 }
    def b = [a: 1].findAll { it.value }
    def c = list.collect { it * 2 }.findAll { it }
    [a, b, c]
}
test()
''', 0, { value -> assert value == [[2, 4], [a: 1], [2, 4]] })

verify('resultSelfReferenceBoundary', '''
def test() {
    def result = [1].collect { result }
    result
}
test()
''', 0, { value -> assert value == [null] })

Map control = converter.transform('def test() { [1].each { for (def item in [1]) { if (item) break; continue } } }', 'LoopControlBoundary.groovy')
assert control.edits.empty
assert control.rejected.any { it.reason.contains('break boundary') && it.reason.contains('continue boundary') }
Map zero = converter.transform('def test() { [1].each { -> 42 } }', 'ZeroParameterBoundary.groovy')
assert zero.edits.empty
Map metaclass = converter.transform('def test() { ArrayList.metaClass.each = { x -> x }; [1].each { it } }', 'MetaclassBoundary.groovy')
assert metaclass.edits.empty
assert metaclass.rejected.any { it.reason.contains('metaclass') }
Map coercion = converter.transform('class CustomList extends ArrayList {};\ndef test() { CustomList items = [1]; items.each { it } }', 'CoercionBoundary.groovy')
assert coercion.edits.empty
Map ambiguous = converter.transform('import example.*\ndef test() { def items = new ArrayList(); items.each { it } }', 'AmbiguousImportBoundary.groovy')
assert ambiguous.edits.empty

File report = new File(args[1])
report.parentFile.mkdirs()
report.text = JsonOutput.prettyPrint(JsonOutput.toJson([
    groovyVersion: GroovySystem.version, cases: reports, structuralOnlyCases: 5,
    compiledClosureReduction: reports.sum { it.transformed }, passed: true
])) + '\n'
println "Parity cases passed: " + reports.size() + "; closure class reduction: " + reports.sum { it.transformed }
