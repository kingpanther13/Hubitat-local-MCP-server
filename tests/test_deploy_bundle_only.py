"""pytest: the watchdog e2e deploy delivers libraries ONLY via the bundle (no per-#include install).

Regression guard for the bundle-only conversion of ``.github/scripts/mcp_watchdog_deploy.sh``. The
deploy used to install each ``#include``d library INDIVIDUALLY (a ``hub_update_library`` /
``hub_create_library`` per ``#include``) AND install the bundle that ships those same libraries --
redundant work plus an extra recompile of the running app. The bundle is now the sole library delivery
path (the HPM "one Update click" flow). If a future edit re-introduces a per-library install, e2e
silently regains the redundant recompile -- this test fails first. It checks the actual tool-CALL RPCs
(``name:"hub_..."``), not bare mentions, so the explanatory comment naming the old loop is fine.
"""

from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "mcp_watchdog_deploy.sh"


def test_deploy_does_not_install_libraries_individually():
    text = SCRIPT.read_text()
    for rpc in ('name:"hub_update_library"', 'name:"hub_create_library"'):
        assert rpc not in text, (
            f"{SCRIPT.name} issues a {rpc} call -- the deploy must deliver #included libraries ONLY via "
            "the bundle (hub_install_bundle). A per-#include library install is the redundant "
            "pre-bundle-only flow and forces an extra recompile of the running app."
        )


def test_deploy_installs_the_bundle():
    text = SCRIPT.read_text()
    assert 'name:"hub_install_bundle"' in text, (
        f"{SCRIPT.name} no longer calls hub_install_bundle -- bundle-only delivery requires it (the "
        "bundle is the sole #include library source)."
    )


def test_deploy_guards_includes_without_a_bundle():
    text = SCRIPT.read_text()
    assert "declares NO bundle to deliver them" in text, (
        f"{SCRIPT.name} dropped the bundle-coverage guard: an app that #includes libraries while the "
        "manifest declares no bundle must fail loudly, not deploy an app whose #includes can't resolve."
    )
