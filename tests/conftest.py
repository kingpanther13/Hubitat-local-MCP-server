# Tell pytest not to collect live-runner / linter / probe source files in tests/.
# These are standalone scripts, not pytest modules:
#   - e2e_test.py: has a `def test(group)` decorator (not a test function)
#     that confuses pytest.
#   - sandbox_lint.py: standalone Groovy-sandbox lint script.
#   - wizard_probe.py: live-hub diagnostic harness; imports `requests` at
#     module level (not in CI's base install). Use the `--with requests`
#     uv invocation to run it.
collect_ignore = ["e2e_test.py", "sandbox_lint.py", "wizard_probe.py"]
collect_ignore_glob = ["wizard_probe_examples/*", "diag_*.py"]
