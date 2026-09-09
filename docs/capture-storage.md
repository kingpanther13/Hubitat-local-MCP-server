# Saved device captures

Saved captures keep their full device values in local Hubitat File Manager files. App state retains a compact index of capture IDs, timestamps, device counts and file references. Listing captures and reporting their count do not load every payload.

A save uploads a new, uniquely named generation and verifies its contents before publishing its index entry. Only then may the previous generation or an evicted capture be deleted. A failed upload or verification leaves the previous capture available. Missing or corrupt committed files produce an explicit error when loaded for restore.

Files use an app-specific `mcp-capture-<appId>-<generation>.json` name. These are restore data: do not manually delete or edit them while their captures are retained. Delete captures through `hub_delete_captured_state` so the index and owned files stay consistent. The configured retention remains 20 captures by default, with a limit of 1–100; saves enforce a reduced limit as well as the ordinary oldest-first policy.

Legacy captures remain readable while migration uploads and verifies one entry at a time. Initialization schedules migration without performing bulk file I/O inside initialization; capture access also starts pending migration. A code-only deployment that does not initialize the app starts migration on the next capture access. Storage failures retain the legacy data and allow retry. Existing IDs, timestamps and device values are preserved, including legacy list entries.

This exchanges repeated app-state serialization of all capture payloads for file I/O when saving or loading a cold capture. A bounded memory cache reduces repeated reads. Hubs with no saved captures receive no bulk-state savings. The regression tests report representative serialized sizes and file-operation counts; they do not establish a general response-time or E2E speedup.
