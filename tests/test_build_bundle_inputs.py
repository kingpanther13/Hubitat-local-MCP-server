"""pytest: the bundle builder must never DISCOVER its own inputs.

`hub-e2e.yml`'s fork-bundle job runs `tools/build-bundle.py` over a FORK PR's `libraries/` in a job
holding `contents: write`. That is only safe because the builder's library list is hardcoded and it
does nothing but read, normalize and deflate those exact paths: a fork adding
`libraries/anything-else.groovy` is ignored. Replacing the list with a glob would quietly turn the job
into a code-delivery surface with repo write, in a diff that reads like a cleanup -- hence this guard.
"""

import re
from pathlib import Path

BUILDER = Path(__file__).resolve().parent.parent / "tools" / "build-bundle.py"

DISCOVERY = (
    r"\bglob\b",
    r"\biterdir\b",
    r"\bos\.listdir\b",
    r"\bos\.walk\b",
    r"\bos\.scandir\b",
    r"\bwalk_dir\b",
)


def test_builder_does_not_discover_library_files():
    text = BUILDER.read_text()
    for pattern in DISCOVERY:
        assert not re.search(pattern, text), (
            f"{BUILDER.name} discovers its inputs ({pattern}) instead of using the hardcoded LIBS "
            "list. The fork-bundle job builds a fork PR's libraries/ with contents: write, so "
            "discovery lets a fork PR add a library file that gets packaged and installed. Keep the "
            "paths explicit."
        )


def test_builder_still_declares_libraries_explicitly():
    text = BUILDER.read_text()
    assert "LIBS = [" in text, f"{BUILDER.name} no longer declares an explicit LIBS list"
    assert text.count('"source": LIB_DIR /') >= 15, (
        f"{BUILDER.name} lost its per-library explicit source paths"
    )
