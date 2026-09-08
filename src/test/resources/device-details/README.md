# Device details fixtures

`current-root-settings.json` preserves the structural contract of the device
details JSON and Vue components observed on firmware 2.5.1.181. All identities,
household labels, data and credentials are synthetic. Unset, explicit-null and
presentation-only entries are synthetic edge cases, not claimed live examples.

The current UI reads top-level `settings` and `inputValues`, separately from
`device`. Boolean and number storage can be strings. Values are matched by
setting name; declaration defaults must not be promoted to saved values.

`unsupported-nested-device-settings.json` represents the incorrect source shape
previously used in writer tests. All three actual captured device responses had
top-level settings; nested-only data must produce an unavailable read status.

The current device UI chunks and capture hashes are recorded in
`resources/hub2-source/README.md`. Raw personal-hub responses are kept outside
the repository and must never be copied into these fixtures.
