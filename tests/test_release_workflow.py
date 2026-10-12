"""Exercise release selection without publishing or modifying version files."""

import json
import os
import subprocess
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("author", ["renovate[bot]", "dependabot[bot]", "kingpanther13"])
@pytest.mark.parametrize("label", [None, "release:patch", "release:minor", "release:major"])
def test_dependency_bots_never_select_a_release(author, label, tmp_path):
    workflow = (ROOT / ".github/workflows/release.yml").read_text()
    step = workflow.split("      - name: Determine release label\n", 1)[1].split("\n      - name:", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1])
    output = tmp_path / "output"
    subprocess.run(["bash", "-eo", "pipefail", "-c", command], check=True,
                   env={**os.environ, "PR_AUTHOR": author, "PR_NUMBER": "42",
                        "LABELS_JSON": json.dumps([{"name": label}] if label else []),
                        "GITHUB_OUTPUT": str(output)})
    lines = output.read_text().splitlines()
    if author in {"renovate[bot]", "dependabot[bot]"} or label is None:
        assert "skip=true" in lines
        assert not any(line.startswith("label=") for line in lines)
    else:
        assert "skip=false" in lines
        assert f"label={label}" in lines
