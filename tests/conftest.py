# Tell pytest not to collect the live-runner and linter source files in tests/.
# e2e_test.py has a `def test(group)` decorator (not a test function) that
# confuses pytest. sandbox_lint.py is a standalone script, not a test module.
collect_ignore = ["e2e_test.py", "sandbox_lint.py"]
