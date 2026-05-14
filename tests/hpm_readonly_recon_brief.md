# HPM Read-Only MCP Tools — Recon Brief

**Source:** `dcmeglio/hubitat-packagemanager` — `apps/Package_Manager.groovy` (v1.8.3, 3860 lines)
**Date:** 2026-05-09

---

## 1. State structure for tracked packages

`state.manifests` is the canonical store. Confirmed at lines 194–195 (initialization) and line 700 (write after install):

```
state.manifests = Map<String manifestUrl, Map manifest>
```

The map key is the **raw manifest URL** (the `packageManifest.json` location the user entered or HPM resolved from a repository listing). It is the same string used as `pkgInstall`. No secondary index exists; all lookups iterate or key on this URL.

After install, four top-level fields are stripped by `minimizeStoredManifests()` (line 3605) to save state space: `licenseFile`, `releaseNotes`, `dateReleased`, `minimumHEVersion`. Everything else from the manifest JSON is preserved in-place.

---

## 2. Per-package metadata shape

Each `state.manifests[url]` value is the downloaded `packageManifest.json` with:
- `packageName` (String) — human-readable name
- `version` (String) — installed version (package-level)
- `betaVersion` (String, optional) — beta version string
- `beta` (Boolean) — whether beta was installed (set by HPM post-download, line 700 area)
- `payPalUrl` (String, optional) — appended by HPM at install time (line 701)
- `author` (String)
- `documentationLink`, `communityLink` (Strings, optional; **not stripped**)
- `apps` (List\<Map\>) — each app entry contains:
  - `id` (UUID String) — manifest-internal identifier
  - `name` (String)
  - `namespace` (String)
  - `location` (String) — source URL for the Groovy file
  - `betaLocation` (String, optional)
  - `required` (Boolean)
  - `primary` (Boolean, optional)
  - `oauth` (Boolean)
  - `version` (String, optional) — per-app version; only present if manifest author included it
  - `heID` (String/Integer) — Hubitat's internal app code ID, **written by HPM at install time** (line 776, 792); null/absent for uninstalled optional apps
  - `alternateNames` (List\<Map\>, optional)
- `drivers` (List\<Map\>) — same structure as apps minus `primary`/`oauth`; `heID` written at line 807, 821
- `files` (List\<Map\>) — file-manager assets; each has `id`, `name`, `location`; **no `heID`** (files go to File Manager, not app/driver code store)

**No library concept.** HPM has no `libraries` key in state or manifest. The manifest schema (`sampleManifest.json`) has `apps`, `drivers`, and `files` only.

**The manifest URL is the only persistent package identifier.** There is no separate `manifestUrl` field inside the stored value — the key IS the manifest URL.

---

## 3. Drift detection signals

**Version strings only — no hashes.** HPM tracks no SHA checksums. Drift detection is purely via version string comparison (`compareVersions`, line 2890).

The `performUpdateCheck` loop (line 1468) does the following, re-fetching the live manifest each time:
1. **Package-level:** compares `manifest.version` (live) vs `state.manifests[key].version` (stored). Any difference triggers an update notice.
2. **Per-app/driver level:** if package versions match, it iterates `manifest.apps` and `manifest.drivers` and compares each item's `version` field (if present). This catches cases where the package version string didn't change but individual component versions did.
3. **Missing required component:** if a required app/driver exists in the live manifest but has `heID == null` in state, flags as "new requirement."
4. **New optional component:** if a manifest item is absent from state entirely (no matching `id`), flags as "new optional."

The `location` field (source URL) on each app/driver is the download URL but is **not compared** — HPM does not detect if the URL changed between installs. No hash of the downloaded source is stored.

For our `get_hpm_drift` tool: compare `state.manifests[key].version` vs a fresh manifest fetch. We cannot replicate full drift detection from state alone without re-fetching live manifests. However, we CAN surface the stored `version` and `heID` presence/absence per component, which is enough to show what HPM last recorded.

---

## 4. statusJson serialization

`/installedapp/statusJson/<hpmAppId>` returns the Hubitat-standard `appState[]` + `appSettings[]` structure. Hubitat serializes `state` entries as follows (platform behavior, not HPM-specific):

- **Simple scalar values** (String, Number, Boolean): stored as their literal value in `appState[].value`
- **Map and List values**: serialized as a **JSON-encoded String** in `appState[].value`

`state.manifests` is a Map-of-Maps. It will appear as a single `appState` entry with `name: "manifests"` and `value` containing a JSON-encoded string of the entire map. The caller must `JSON.parse(entry.value)` to reconstruct the nested structure.

**Practical implication:** the MCP tool reads `appState`, finds the entry where `name == "manifests"`, deserializes the value, and iterates the resulting map. Do not expect the nested structure to arrive pre-parsed.

Size caution: `state.manifests` grows with every installed package. On a heavily-loaded hub with 50+ packages it may be 200–500 KB of serialized JSON inside the value string. Plan for chunked/streamed handling or at minimum surface the count and let the caller request detail per-package.

---

## 5. HPM appId discovery

HPM self-identifies at line 3526:

```groovy
def appId = appsInstalled.find { i -> i.title == "Hubitat Package Manager" && i.namespace == "dcm.hpm"}?.id
```

The app ID is **not fixed** — it is whatever Hubitat assigned at install time. The MCP tool must discover it by calling the installed-apps list and filtering on `name == "Hubitat Package Manager"` and `namespace == "dcm.hpm"`. The MCP server's `manage_installed_apps` tool (or a raw `list_installed_apps` call) returns this. There is no convention like "always app 1."

---

## Proposed tool response shapes

### `list_hpm_packages`

```json
{
  "packages": [
    {
      "manifestUrl": "https://raw.githubusercontent.com/foo/bar/main/packageManifest.json",
      "packageName": "BOND Home Integration",
      "version": "1.1.2",
      "beta": false,
      "author": "Dominick Meglio",
      "apps": [
        { "id": "bf7c20fb-...", "name": "Sample App", "heID": "142", "required": true, "version": null }
      ],
      "drivers": [
        { "id": "163f6821-...", "name": "Sample Driver", "heID": "89", "required": true, "version": null }
      ],
      "files": [
        { "id": "3f7ffb28-...", "name": "myfile.js" }
      ]
    }
  ],
  "count": 1,
  "hpmAppId": "38"
}
```

### `get_hpm_drift`

Since HPM stores no hashes, drift is "what HPM last recorded" vs "what is currently installed on the hub." The tool can surface:

```json
{
  "manifestUrl": "https://raw.githubusercontent.com/foo/bar/main/packageManifest.json",
  "packageName": "BOND Home Integration",
  "storedVersion": "1.1.2",
  "components": [
    {
      "type": "app",
      "name": "Sample App",
      "heID": "142",
      "storedVersion": null,
      "installed": true
    },
    {
      "type": "driver",
      "name": "Sample Driver",
      "heID": "89",
      "storedVersion": null,
      "installed": true
    }
  ],
  "note": "Drift detection is version-string only. HPM stores no file hashes. A 'null' storedVersion means the manifest author did not include per-component versions."
}
```

To detect actual drift (installed version vs manifest version), the tool would need to re-fetch the live manifest — or compare `heID` presence (null = component not installed despite being required).

---

## Caveats and gotchas

1. **`state.manifests` key is the raw URL string.** If a user installs the same package from two different URL spellings (http vs https, trailing slash), they get two entries. No dedup.

2. **`heID` is a String on some paths, Integer on others.** HPM extracts it from HTML scraped from `/app/list` (returns String from regex) and from the Location header after POST (also String). Treat as String to avoid type comparison failures.

3. **Per-component `version` is optional.** Many manifest authors omit per-app/driver `version` fields. The version in that case is only the package-level `version`. Do not assume per-component versions exist.

4. **`files` entries have no `heID`.** File Manager assets are tracked by their manifest `id` and `name` only; no Hubitat-internal numeric ID is stored.

5. **`minimizeStoredManifests` strips 4 fields.** `licenseFile`, `releaseNotes`, `dateReleased`, `minimumHEVersion` are removed from state after every install/update. Do not expect them in statusJson output.

6. **statusJson value is double-encoded.** The `manifests` key value is a JSON string inside the JSON response. Parse once for the outer structure, parse again for the manifests map.

7. **appId discovery requires a live call.** No static convention. Filter on `name == "Hubitat Package Manager"` AND `namespace == "dcm.hpm"` — the name match alone is fragile if a user renamed the app.

8. **No libraries.** The manifest schema and state both have no library concept. The question's assumption about `list installed libraries` does not apply to HPM.
