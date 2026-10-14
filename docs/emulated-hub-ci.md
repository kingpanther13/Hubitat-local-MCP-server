# Software E2E

The Software E2E lane consumes the public `Software E2E` commit status for the
exact revision. The result provider tests the production MCP app over HTTP in an
external software environment using the existing Python E2E registry and the
official MCP SDK assertions. Additional software tests can evolve independently.
A passing consumer requires a successful result; missing, failed, or unavailable
results cannot establish coverage.

The workflow runs on PR open, update, and reopen, pushes to `main`, and manual
dispatch. Automatic PR execution currently supports same-repository heads authored
by `kingpanther13`; other sources show explicit ineligibility and skip the result
job until trusted execution is supported. Manual dispatch checks the selected
revision's existing result. PR checks use the PR head SHA rather than a synthetic
merge revision. A result for another revision is never accepted.

The public workflow has only contents/status read permissions and receives no
hub credentials. It forwards no external descriptions, links, or raw diagnostics.
It waits up to 130 minutes for a terminal result, including scheduling time when
the status has not appeared yet. Its output is a fixed passed, failed, or
unavailable message.

This lane has no label gates. `e2e:skip` affects the physical-hub workflow only.
The physical-hub lane supplies evidence for radios, hardware, and cloud behavior
that a software result alone does not establish. A configured status consumer is
not evidence that the full suite has passed; the tested revision and terminal
result determine that outcome.
