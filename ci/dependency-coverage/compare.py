"""Compare instrumented application probes and JUnit inventories; no hub access."""
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
results = []


def inventory(folder):
    cases = collections.Counter()
    for path in folder.rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().findall('testcase'):
            name = re.sub(r'@[0-9a-fA-F]{4,}\b', '@OBJECT', case.get('name'))
            outcome = ('skipped' if case.find('skipped') is not None else
                       'failed' if case.find('failure') is not None or case.find('error') is not None
                       else 'passed')
            cases[(case.get('classname'), name, outcome)] += 1
    if not cases:
        raise ValueError(f'No JUnit cases in {folder}')
    return cases


for baseline_dir in sorted(root.glob('coverage-*-baseline')):
    candidate_dir = baseline_dir.with_name(baseline_dir.name.removesuffix('-baseline') + '-candidate')
    before = json.loads(next(baseline_dir.rglob('coverage.json')).read_text())
    after = json.loads(next(candidate_dir.rglob('coverage.json')).read_text())
    old = {(c['name'], c['id']): c for c in before['classes']}
    new = {(c['name'], c['id']): c for c in after['classes']}
    changed = [{'class': k, 'before': old[k], 'after': new[k]}
               for k in sorted(old.keys() & new.keys()) if old[k] != new[k]]
    old_tests, new_tests = inventory(baseline_dir), inventory(candidate_dir)
    item = {'lane': baseline_dir.name.removeprefix('coverage-').removesuffix('-baseline'),
            'baseline_classes': len(old), 'candidate_classes': len(new),
            'missing_classes': sorted(old.keys() - new.keys()),
            'added_classes': sorted(new.keys() - old.keys()), 'changed_classes': changed,
            'baseline_cases': sum(old_tests.values()), 'candidate_cases': sum(new_tests.values()),
            'missing_or_changed_cases': list((old_tests-new_tests).items()),
            'added_or_changed_cases': list((new_tests-old_tests).items())}
    item['identical'] = not any(item[k] for k in ['missing_classes', 'added_classes',
        'changed_classes', 'missing_or_changed_cases', 'added_or_changed_cases'])
    results.append(item)
    print(json.dumps({k: v if k != 'changed_classes' else len(v) for k, v in item.items()}))
if not results:
    raise SystemExit('No baseline coverage artifacts found')
(root / 'comparison.json').write_text(json.dumps(results, indent=2))
if not all(r['identical'] for r in results):
    raise SystemExit('Coverage or test inventory differs; inspect comparison.json')
