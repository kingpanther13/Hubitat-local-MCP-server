"""Fail on instrumentation errors, including stderr hidden in JUnit XML."""
import re
import sys
from pathlib import Path

ERROR = re.compile(r'IllegalClassFormatException|Error while instrumenting|java.lang.instrument ASSERTION')


def check(paths):
    files = []
    for path in paths:
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(path.rglob('TEST-*.xml'))
    assert any(p.name.startswith('TEST-') for p in files), 'No test XML to inspect'
    logs = [p for p in files if p.name == 'coverage-run.log']
    assert logs, 'Missing Gradle execution log'
    for path in files:
        content = path.read_text()
        assert not ERROR.search(content), f'Instrumentation error in {path}'
        if path in logs:
            assert 'BUILD SUCCESSFUL' in content, f'Gradle did not succeed: {path}'


if __name__ == '__main__':
    check([Path(p) for p in sys.argv[1:]])
    print('Gradle succeeded; no instrumentation errors in logs or JUnit XML')
