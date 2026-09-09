# Temporary device captures

Captures serve the legacy custom rule engine's `capture_state` and `restore_state` actions. They remember switch, level and color settings before a temporary change; ordinary device reads and native Rule Machine do not use this store.

Captures live only in class-static memory, isolated and synchronized per installed parent app. Separate rule events can share a named capture. Hub restart or app code reload loses the snapshots; a later restore of a missing snapshot logs a warning and does nothing. Capture again before restoring after a restart. Saving app settings does not deliberately clear the memory store, but any platform reload may clear it.

Existing captures in `state` or `atomicState` are imported into memory once on initialization or first access, preserving their IDs, timestamps and full values. The old persisted key is then removed. Captures are not written to app state or File Manager, and there is no durable index or background file migration/cleanup. Memory holds the payloads until deletion, retention eviction or class reload.

The existing configurable count limit remains 20 by default, clamped to 1-100. Saves evict the oldest other captures when necessary. This is a count limit, not a byte limit. Caller mutations cannot change a stored snapshot.

Removing persisted capture payloads reduces app-state serialization work only on hubs that have those payloads. No overall E2E or request-latency improvement has been demonstrated. Legacy E2E and BAT coverage remains frozen; storage maintenance is checked with unit tests.
