# Tell pytest not to collect live-runner / linter / probe source files in tests/.
# These are standalone scripts, not pytest modules:
#   - e2e_test.py: has a `def test(group)` decorator (not a test function)
#     that confuses pytest.
#   - sandbox_lint.py: standalone Groovy-sandbox lint script.
#   - wizard_probe.py: live-hub diagnostic harness, not a pytest module — it
#     talks to a real hub on import-time config. Run it via the `--with requests`
#     uv invocation.
#   - sdk_conformance_test.py: live-hub harness driven by the official MCP Python
#     SDK. Its name matches pytest's default `*_test.py` pattern, but it is a
#     standalone script whose import guard EXITS when the pinned `mcp` package is
#     absent (deliberately a failure, never a skip). The Python Tests workflow does
#     not install that SDK — only the e2e job does — so collecting it here would
#     turn a green lane red for the wrong reason.
collect_ignore = ["e2e_test.py", "sandbox_lint.py", "wizard_probe.py", "sdk_conformance_test.py"]
collect_ignore_glob = ["wizard_probe_examples/*", "diag_*.py"]
