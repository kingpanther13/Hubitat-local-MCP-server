# Tell pytest not to collect live-runner / linter / probe source files in tests/.
# These are standalone scripts, not pytest modules:
#   - e2e_test.py: has a `def test(group)` decorator (not a test function)
#     that confuses pytest.
#   - sandbox_lint.py: standalone Groovy-sandbox lint script.
#   - wizard_probe.py: live-hub diagnostic harness, not a pytest module — it
#     talks to a real hub on import-time config. Run it via the `--with requests`
#     uv invocation.
collect_ignore = ["e2e_test.py", "sandbox_lint.py", "wizard_probe.py"]
collect_ignore_glob = ["wizard_probe_examples/*", "diag_*.py"]
