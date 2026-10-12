"""Compare instrumented application probes and JUnit inventories; no hub access."""
import collections
import json
import hashlib
import io
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from check_logs import check

root = Path(sys.argv[1])
results = []
incomplete_pairs = []
scope = sys.argv[2] if len(sys.argv) > 2 else 'full'
expected_lanes = ({'primary', 'groovy25'} if scope == 'smoke' else
                  {'primary-r1', 'primary-r2', 'groovy25-r1', 'groovy25-r2'} if scope == 'focused' else
                  {'primary', 'flat', 'strict', 'strict-flat', 'groovy25'})


def normalize(name):
    return re.sub(r'@[0-9a-fA-F]{4,}\b', '@OBJECT', name)


def execution(path, application_ids):
    """Read JaCoCo's documented exec format; retain per-class probe bitsets."""
    stream = io.BytesIO(path.read_bytes())

    def utf():
        size, = struct.unpack('>H', stream.read(2))
        return stream.read(size)

    def varint():
        result = shift = 0
        while True:
            value = stream.read(1)[0]
            result |= (value & 127) << shift
            if value < 128:
                return result
            shift += 7

    probes = {}
    while block := stream.read(1):
        if block == b'\x01':
            assert stream.read(4) == bytes.fromhex('c0c01007')
        elif block == b'\x10':
            utf()
            assert len(stream.read(16)) == 16
        elif block == b'\x11':
            class_id = format(struct.unpack('>Q', stream.read(8))[0], 'x')
            utf()
            count = varint()
            bits = stream.read((count + 7) // 8)
            assert len(bits) == (count + 7) // 8
            if class_id in application_ids and any(bits):
                probes[class_id] = {'count': count, 'hits': int.from_bytes(bits, 'little').bit_count(),
                                    'sha256': hashlib.sha256(bits).hexdigest()}
        else:
            raise ValueError(f'Unknown JaCoCo block {block!r}')
    return probes


def iterations(folder, classes):
    ids = {key[1] for key in classes}
    records = {}
    for path in folder.rglob('iterations/*.json'):
        metadata = json.loads(path.read_text())
        key = (metadata['spec'], metadata['feature'], metadata['iteration'])
        assert key not in records, f'Duplicate iteration: {key}'
        records[key] = execution(path.with_name(metadata['file']), ids)
    assert records, f'No per-test coverage: {folder}'
    return records


def unmeasured_passes(folder, tests):
    measured = collections.Counter()
    for path in folder.rglob('iterations/*.json'):
        metadata = json.loads(path.read_text())
        measured[(metadata['spec'], normalize(metadata['display']))] += 1
    passed = collections.Counter({(spec, name): count for (spec, name, outcome), count in tests.items()
                                  if outcome == 'passed'})
    return list((passed - measured).items())


def inventory(folder):
    cases = collections.Counter()
    for path in folder.rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().findall('testcase'):
            name = normalize(case.get('name'))
            outcome = ('skipped' if case.find('skipped') is not None else
                       'failed' if case.find('failure') is not None or case.find('error') is not None
                       else 'passed')
            cases[(case.get('classname'), name, outcome)] += 1
    if not cases:
        raise ValueError(f'No JUnit cases in {folder}')
    return cases


for baseline_dir in sorted(root.glob('coverage-*-baseline')):
    candidate_dir = baseline_dir.with_name(baseline_dir.name.removesuffix('-baseline') + '-candidate')
    if not candidate_dir.is_dir():
        incomplete_pairs.append(baseline_dir.name)
        continue
    for folder in (baseline_dir, candidate_dir):
        check([folder, *folder.rglob('coverage-run.log')])
    before = json.loads(next(baseline_dir.rglob('coverage.json')).read_text())
    after = json.loads(next(candidate_dir.rglob('coverage.json')).read_text())
    old = {(c['name'], c['id']): c for c in before['classes']}
    new = {(c['name'], c['id']): c for c in after['classes']}
    changed = [{'class': k, 'before': old[k], 'after': new[k]}
               for k in sorted(old.keys() & new.keys()) if old[k] != new[k]]
    old_tests, new_tests = inventory(baseline_dir), inventory(candidate_dir)
    old_iterations, new_iterations = iterations(baseline_dir, old), iterations(candidate_dir, new)
    changed_iterations = [{'test': key, 'before': old_iterations[key], 'after': new_iterations[key]}
                          for key in sorted(old_iterations.keys() & new_iterations.keys())
                          if old_iterations[key] != new_iterations[key]]
    item = {'lane': baseline_dir.name.removeprefix('coverage-').removesuffix('-baseline'),
            'baseline_classes': len(old), 'candidate_classes': len(new),
            'missing_classes': sorted(old.keys() - new.keys()),
            'added_classes': sorted(new.keys() - old.keys()), 'changed_classes': changed,
            'baseline_cases': sum(old_tests.values()), 'candidate_cases': sum(new_tests.values()),
            'missing_or_changed_cases': list((old_tests-new_tests).items()),
            'added_or_changed_cases': list((new_tests-old_tests).items())}
    item.update(baseline_iterations=len(old_iterations), candidate_iterations=len(new_iterations),
                missing_iterations=sorted(old_iterations.keys()-new_iterations.keys()),
                added_iterations=sorted(new_iterations.keys()-old_iterations.keys()),
                changed_iterations=changed_iterations,
                unmeasured_baseline_tests=unmeasured_passes(baseline_dir, old_tests),
                unmeasured_candidate_tests=unmeasured_passes(candidate_dir, new_tests),
                failed_cases=[k for k in old_tests.keys() | new_tests.keys() if k[2] == 'failed'])
    item['identical'] = not any(item[k] for k in ['missing_classes', 'added_classes',
        'changed_classes', 'missing_or_changed_cases', 'added_or_changed_cases',
        'missing_iterations', 'added_iterations', 'changed_iterations', 'failed_cases',
        'unmeasured_baseline_tests', 'unmeasured_candidate_tests'])
    results.append(item)
    print(json.dumps({k: len(v) if isinstance(v, list) else v for k, v in item.items()}))
if not results:
    raise SystemExit('No baseline coverage artifacts found')
(root / 'comparison.json').write_text(json.dumps(results, indent=2))
assert not incomplete_pairs, f'Incomplete artifact pairs: {incomplete_pairs}'
assert {r['lane'] for r in results} == expected_lanes, 'Missing or unexpected coverage lane'
if not all(r['identical'] for r in results):
    raise SystemExit('Coverage or test inventory differs; inspect comparison.json')
