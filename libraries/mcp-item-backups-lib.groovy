library(name: "McpItemBackupsLib", namespace: "mcp", author: "kingpanther13", description: "Backup tool implementations for the MCP Rule Server: source-code backups (hub_list_backups/hub_get_backup/hub_restore_backup) AND whole-hub database backups (hub_create_backup/hub_delete_backup + the hub-DB scope of list/restore) -- issue #259 item #1. #include'd by the main app; gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListItemBackups(args = null) {
    args = args ?: [:]
    // `scope` folds the WHOLE-HUB database backups (issue #259 item #1) into this source-backup list
    // tool, so callers don't juggle two "list backups" tools. Default "source" keeps prior behavior.
    def scope = (args.scope ?: "source").toString()
    if (!(scope in ["source", "hub_local", "hub_cloud", "hub", "all"])) {
        throw new IllegalArgumentException("scope must be one of: source, hub_local, hub_cloud, hub, all")
    }
    def hubSections = [:]
    if (scope in ["hub_local", "hub_cloud", "hub", "all"]) {
        def hb = _listHubBackups(scope in ["hub_local", "hub", "all"], scope in ["hub_cloud", "hub", "all"])
        if (hb.local != null) hubSections.hubLocalBackups = hb.local
        if (hb.cloud != null) hubSections.hubCloudBackups = hb.cloud
        if (hb.errors) { hubSections.hubBackupErrors = hb.errors; hubSections.partial = true }
    }
    if (scope == "source") return _listSourceItemBackups(args)
    if (!(scope in ["source", "all"])) {
        return ([scope: scope] + hubSections + [note: "Whole-hub database backups. Restore via hub_restore_backup (scope=hub_local|hub_cloud); delete via hub_delete_backup."])
    }
    // scope == "all": source-code backups + the hub-DB sections in one response.
    return ([scope: scope] + _listSourceItemBackups(args) + hubSections)
}

private _listSourceItemBackups(args) {
    def manifest = atomicState.itemBackupManifest ?: [:]

    if (manifest.isEmpty()) {
        return [
            backups: [],
            count: 0,
            total: 0,
            message: "No item backups exist yet. Backups are created automatically when you use hub_update_app, hub_update_driver, hub_update_library, or hub_delete_item.",
            maxBackups: 20,
            storage: "Backups are stored as .groovy files in the hub's File Manager. You can access them at http://<HUB_IP>/local/<filename> or via Hubitat > Settings > File Manager.",
            howToRestore: "Use 'hub_get_backup' to retrieve source code, then 'hub_restore_backup' to restore (apps/drivers). For deleted apps or drivers, use 'hub_create_app' or 'hub_create_driver' with the backup source. For deleted libraries, use 'hub_create_library' with the backup source."
        ]
    }

    def backupList = manifest.collect { key, entry ->
        def base = [
            backupKey: key,
            type: entry.type,
            id: entry.id,
            fileName: entry.fileName,
            timestampEpoch: entry.timestamp ?: 0,
            timestamp: formatTimestamp(entry.timestamp),
            age: formatAge(entry.timestamp),
            sourceLength: entry.sourceLength ?: 0,
            directDownload: "http://<HUB_IP>/local/${entry.fileName}"
        ]
        // App/driver entries carry version + sourceLength; rm-rule entries
        // carry reason + appLabel. Surface the right metadata per type so
        // the response stays informative without forcing callers to know
        // what missing fields mean.
        if (entry.type == "rm-rule") {
            base.ruleId = entry.ruleId
            base.appLabel = entry.appLabel
            base.reason = entry.reason
        } else {
            base.version = entry.version
        }
        return base
    }.sort { a, b -> (b.timestampEpoch <=> a.timestampEpoch) } // Newest first

    def cursor = args?.cursor
    def paged = _paginateList(backupList, cursor, 50, "hub_list_backups")
    def result = [
        backups: paged.page,
        count: paged.page.size(),
        total: backupList.size(),
        maxBackups: 20,
        storage: "Backup files are stored in the hub's local File Manager (Settings > File Manager). Files persist even if MCP is uninstalled.",
        howToRestore: "Use 'hub_restore_backup' with a backupKey to restore apps/drivers via MCP. For library backups, use 'hub_update_library' with sourceFile mode instead. Or download the .groovy file from File Manager and paste it into Apps Code / Drivers Code / Libraries code manually.",
        manualRestore: "Go to Hubitat > Settings > File Manager to see backup files. Download a file, then go to Apps Code (or Drivers Code, or FOR DEVELOPERS > Libraries code) > select the item > paste the source > click Save."
    ]
    if (cursor != null && paged.nextCursor != null) result.nextCursor = paged.nextCursor
    return result
}

def toolGetItemBackup(args) {
    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123', 'driver_456', or 'library_42')")

    def manifest = atomicState.itemBackupManifest ?: [:]
    def entry = manifest[args.backupKey]

    if (!entry) {
        mcpLog("debug", "hub-admin", "Backup key '${args.backupKey}' not found in manifest")
        def availableKeys = manifest.keySet().sort()
        return [
            error: "No backup found for key '${args.backupKey}'",
            availableBackups: availableKeys.isEmpty() ? "None -- no backups exist yet" : availableKeys.join(", "),
            hint: "Use 'hub_list_backups' to see all available backups with details"
        ]
    }

    // Read source code from hub's local File Manager
    def source
    try {
        def bytes = downloadHubFile(entry.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        source = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to read backup file '${entry.fileName}'", e)
        return [
            error: "Backup file '${entry.fileName}' could not be read: ${e.message}",
            backupKey: args.backupKey,
            suggestion: "The file may have been deleted from File Manager. Check Hubitat > Settings > File Manager.",
            directDownload: "http://<HUB_IP>/local/${entry.fileName}"
        ]
    }

    def result = [
        backupKey: args.backupKey,
        type: entry.type,
        id: entry.id,
        fileName: entry.fileName,
        version: entry.version,
        timestamp: formatTimestamp(entry.timestamp),
        age: formatAge(entry.timestamp),
        sourceLength: source.length(),
        directDownload: "http://<HUB_IP>/local/${entry.fileName}"
    ]

    // Only include source in response if it fits within the hub's response limit
    // For large files, direct the user to download from File Manager instead
    if (source.length() <= 60000) {
        result.source = source
    } else {
        result.sourceTooLargeForResponse = true
        result.message = "Source code is ${source.length()} chars — too large for an MCP response. Download it directly from File Manager instead."
        result.manualDownload = "Go to http://<HUB_IP>/local/${entry.fileName} in your browser, or find it in Hubitat > Settings > File Manager."
    }

    if (entry.type == "app") {
        result.howToRestore = "To restore via MCP: call 'hub_restore_backup' with backupKey='${args.backupKey}' and confirm=true. To restore manually: download ${entry.fileName} from File Manager, go to Hubitat > Apps Code > app ID ${entry.id} > paste source > Save."
    } else if (entry.type == "library") {
        result.howToRestore = "Library backups cannot be restored via hub_restore_backup. To restore: call 'hub_update_library' with libraryId='${entry.id}' and sourceFile='${entry.fileName}' (confirm=true). To restore manually: download ${entry.fileName} from File Manager, go to Hubitat > FOR DEVELOPERS > Libraries code > library ID ${entry.id} > paste source > Save."
    } else {
        result.howToRestore = "To restore via MCP: call 'hub_restore_backup' with backupKey='${args.backupKey}' and confirm=true. To restore manually: download ${entry.fileName} from File Manager, go to Hubitat > Drivers Code > driver ID ${entry.id} > paste source > Save."
    }

    return result
}

def toolRestoreItemBackup(args) {
    args = args ?: [:]
    // `scope` folds WHOLE-HUB database restore (issue #259 item #1) into this tool. hub_local/hub_cloud
    // REPLACE THE ENTIRE HUB DATABASE and REBOOT the hub -- far higher blast radius than a source
    // re-paste -- so they are confirm-gated here too. Default "source" = app/driver/rule restore.
    def scope = (args.scope ?: "source").toString()
    if (scope in ["hub_local", "hub_cloud"]) {
        requireDestructiveConfirm(args.confirm)
        return _restoreHubBackup(scope, args)
    }
    if (scope == "hub_uploaded") {
        requireDestructiveConfirm(args.confirm)
        return _restoreUploadedBackup(args)
    }
    if (scope != "source") {
        throw new IllegalArgumentException("scope must be 'source' (default), 'hub_local', 'hub_cloud', or 'hub_uploaded'.")
    }
    requireDestructiveConfirm(args.confirm)

    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123', 'driver_456', 'library_42', or 'rm-rule_<id>_<ts>')")

    def manifest = atomicState.itemBackupManifest ?: [:]
    def entry = manifest[args.backupKey]

    if (!entry) {
        mcpLog("debug", "hub-admin", "Restore: backup key '${args.backupKey}' not found in manifest")
        def availableKeys = manifest.keySet().sort()
        return [
            success: false,
            error: "No backup found for key '${args.backupKey}'",
            availableBackups: availableKeys.isEmpty() ? "None" : availableKeys.join(", ")
        ]
    }

    // Library restores don't ride this path -- the version fetch + pre-restore backup here
    // are wired to /app|driver/ajax/code, which has no library twin.
    // Direct the caller to use hub_create_library or hub_update_library with the backup source.
    if (entry.type == "library") {
        return [
            success: false,
            error: "Library backups cannot be restored via hub_restore_backup -- use hub_create_library or hub_update_library with the backup source from '${entry.fileName}' instead.",
            backupKey: args.backupKey,
            type: "library",
            backupFile: entry.fileName,
            directDownload: "http://<HUB_IP>/local/${entry.fileName}",
            hint: "Download the backup from File Manager or use hub_get_backup to retrieve the source, then call hub_update_library with sourceFile mode."
        ]
    }

    // RM rule snapshots use a different restore path (re-apply settings via
    // the wizard wire format, not POST source code). Dispatch by type.
    if (entry.type == "rm-rule") {
        try {
            return _rmRestoreFromBackup(entry)
        } catch (Exception e) {
            mcpLogError("hub-admin", "RM rule restore failed for key ${args.backupKey}", e)
            return [success: false, error: e.message, backupKey: args.backupKey, type: "rm-rule"]
        }
    }

    // Read the backup source from File Manager
    def source
    try {
        def bytes = downloadHubFile(entry.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        source = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to read backup file '${entry.fileName}' for restore", e)
        return [
            success: false,
            error: "Backup file '${entry.fileName}' could not be read: ${e.message}",
            backupKey: args.backupKey,
            suggestion: "The file may have been deleted from File Manager. Check Hubitat > Settings > File Manager."
        ]
    }

    if (!source) {
        mcpLog("warn", "hub-admin", "Backup file '${entry.fileName}' is empty -- cannot restore")
        return [
            success: false,
            error: "Backup file exists but is empty",
            backupKey: args.backupKey
        ]
    }

    mcpLog("info", "hub-admin", "Restoring ${entry.type} ID ${entry.id} from backup file ${entry.fileName} (version ${entry.version}, ${formatTimestamp(entry.timestamp)})")

    // Save a copy of the entry before modifying manifest
    def entryCopy = entry.clone()

    // Before restoring, back up the CURRENT source under a different filename so it's not overwritten
    // (the original backup file uses the same deterministic name, so backupItemSource would overwrite it)
    def preRestoreFileName = "mcp-prerestore-${entryCopy.type}-${entryCopy.id}.groovy"
    def preRestoreBackupKey = "prerestore_${entryCopy.type}_${entryCopy.id}"
    try {
        def ajaxPath = (entryCopy.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def responseText = hubInternalGet(ajaxPath, [id: entryCopy.id])
        if (responseText) {
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            if (parsed.source == source) {
                // The live source already equals the backup being restored (a retry
                // after a dropped response): capturing it now would overwrite the
                // real pre-restore undo with the just-restored content, silently
                // destroying the only undo point. Keep the existing undo file.
                mcpLog("info", "hub-admin", "Current source already matches the backup being restored -- keeping the existing pre-restore undo")
            } else if (parsed.source) {
                uploadHubFile(preRestoreFileName, parsed.source.getBytes("UTF-8"))
                // atomicState read-modify-write: read full map, mutate locally, write back.
                def mfst = atomicState.itemBackupManifest ?: [:]
                mfst[preRestoreBackupKey] = [
                    type: entryCopy.type, id: entryCopy.id, fileName: preRestoreFileName,
                    version: parsed.version, timestamp: now(), sourceLength: parsed.source.length()
                ]
                atomicState.itemBackupManifest = mfst
                mcpLog("info", "hub-admin", "Pre-restore backup saved: ${preRestoreFileName} (version ${parsed.version}, ${parsed.source.length()} chars)")
            }
        }
    } catch (Exception preBackupErr) {
        mcpLog("warn", "hub-admin", "Could not create pre-restore backup: ${preBackupErr.message} -- proceeding with restore anyway")
    }

    // Restoring the MCP server's OWN code drops the response exactly like a self-update
    // (the recompile kills the in-flight request), so it gets the same empty-body leniency
    // and lastSelfDeploy stash the update path has. Computed before the try so the
    // exception path can stash too.
    boolean isSelfRestore = false
    if (entryCopy.type == "app") {
        def selfIds = [app?.id?.toString(), _resolveSelfAppClassId()?.toString()].findAll { it != null }
        isSelfRestore = selfIds.contains(entryCopy.id?.toString())
    }

    // Now push the backup source directly via the hub internal API (bypass toolUpdateAppCode to avoid
    // its backupItemSource call which would overwrite our original backup file)
    try {
        // Fetch current version for optimistic locking
        def ajaxPath = (entryCopy.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def versionResp = hubInternalGet(ajaxPath, [id: entryCopy.id])
        def currentVersion = null
        if (versionResp) {
            try {
                def vParsed = new groovy.json.JsonSlurper().parseText(versionResp)
                currentVersion = vParsed.version
            } catch (Exception vErr) { /* proceed without version */ }
        }

        // Same JSON save endpoint the update path uses; id present => in-place update.
        def savePath = (entryCopy.type == "app") ? "/app/saveOrUpdateJson" : "/driver/saveOrUpdateJson"

        def parsed = hubInternalPostJson(savePath, groovy.json.JsonOutput.toJson([
            id: entryCopy.id as Integer,
            source: source,
            version: currentVersion ?: entryCopy.version
        ]))

        def success = false
        def errorMsg = null
        if (parsed instanceof Map) {
            success = parsed.success == true
            if (success && parsed.id != null && parsed.id.toString() != entryCopy.id.toString()) {
                success = false
                errorMsg = "Hub reported success but saved to ${entryCopy.type} id ${parsed.id} instead of the targeted id ${entryCopy.id} -- a duplicate code entry may have been created."
            } else if (!success) {
                errorMsg = parsed.message ?: parsed.errorMessage ?: "hub response lacked success=true: ${parsed.toString().take(200)}"
            }
        } else if (parsed == null) {
            // null is strictly an EMPTY body (non-JSON bodies arrive as the _unparseable sentinel
            // and fail above). Lenient only for a self-restore; otherwise fail closed.
            success = isSelfRestore
            if (!success) errorMsg = "Empty response from ${savePath} — restore may or may not have applied. Verify the ${entryCopy.type} source before retrying."
        } else {
            errorMsg = "Unexpected response shape from ${savePath}: ${parsed.toString().take(200)}"
        }

        if (isSelfRestore) {
            try {
                def stash = [
                    success: success,
                    error: success ? null : (errorMsg ?: "Restore failed -- the hub returned an error"),
                    sourceMode: "restore",
                    importUrl: null,
                    sourceLength: source.length(),
                    at: now()
                ]
                if (success && parsed == null) stash.assumed = true
                atomicState.lastSelfDeploy = stash
            } catch (Exception stashErr) {
                mcpLog("error", "hub-admin", "lastSelfDeploy stash write failed -- self-restore outcome record lost: ${stashErr}")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "Restore succeeded: ${entryCopy.type} ID ${entryCopy.id} restored to version ${entryCopy.version}")
            def restoreResult = [
                success: true,
                message: "Restored ${entryCopy.type} ID ${entryCopy.id} to version ${entryCopy.version} (backup from ${formatTimestamp(entryCopy.timestamp)})",
                type: entryCopy.type,
                id: entryCopy.id,
                restoredVersion: entryCopy.version,
                preRestoreBackup: preRestoreBackupKey,
                preRestoreFile: preRestoreFileName,
                undoHint: "To undo this restore, use 'hub_restore_backup' with backupKey='${preRestoreBackupKey}'"
            ]
            if (isSelfRestore && parsed == null) {
                restoreResult.assumed = true
                restoreResult.note = "This restored the MCP server's own code, so the hub's response was dropped by the recompile -- success is inferred, not hub-confirmed. Verify via hub_get_info (lastSelfDeploy) or hub_get_source."
            }
            return restoreResult
        } else {
            mcpLog("error", "hub-admin", "Restore failed for ${entryCopy.type} ID ${entryCopy.id}: ${errorMsg ?: 'unknown error'}")
            return [
                success: false,
                error: "Restore failed: ${errorMsg ?: 'unknown error'}",
                backupKey: args.backupKey,
                message: "The backup has been preserved -- you can try again or restore manually.",
                directDownload: "http://<HUB_IP>/local/${entryCopy.fileName}"
            ]
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "Restore failed with exception for ${entryCopy.type} ID ${entryCopy.id}", e)
        if (isSelfRestore) {
            try {
                atomicState.lastSelfDeploy = [
                    success: false,
                    error: "Restore failed: ${e.message}",
                    sourceMode: "restore",
                    importUrl: null,
                    sourceLength: (source != null ? source.length() : 0),
                    at: now()
                ]
            } catch (Exception stashErr) {
                mcpLog("error", "hub-admin", "lastSelfDeploy stash write failed -- self-restore outcome record lost: ${stashErr}")
            }
        }
        return [
            success: false,
            error: "Restore failed: ${e.message}",
            backupKey: args.backupKey,
            message: "The backup has been preserved -- you can try again or restore manually.",
            directDownload: "http://<HUB_IP>/local/${entryCopy.fileName}"
        ]
    }
}

// ==================== Hub-DB (whole-hub database) backup tools — issue #259 item #1 ====================
// These manage the WHOLE-HUB database backup (settings/devices/automations/state), a DIFFERENT domain
// from the source-code item backups above. Wire format reverse-engineered from resources/hub2-source
// (vue-hub2.min.js): list = GET /hub2/localBackups (array) and GET /hub2/cloudBackups?force= ({backups:[]});
// restore = GET /hub2/restoreLocalBackup?fileName= and GET /hub2/restoreCloudBackup?p=<password>&t=<ms>
// (BOTH reboot the hub); delete = GET /hub2/deleteLocalBackup?fileName= and GET /hub2/deleteCloudBackup?path=;
// schedule = POST /hub2/updateBackupSchedule. Upload/restore-uploaded are browser multipart .lzf uploads
// (no headless MCP path) and are intentionally NOT implemented.

def toolCreateHubBackup(args) {
    args = args ?: [:]

    // Folded-in SCHEDULE update (no separate tool): when a `schedule` object is supplied, set the
    // hub's auto-backup schedule via /hub2/updateBackupSchedule. With scheduleOnly=true we ONLY set
    // the schedule and skip creating a backup now; otherwise the create-now path also runs.
    def scheduleUpdated = false
    if (args.schedule != null) {
        def sched = _setHubBackupSchedule(args.schedule)
        if (!(sched instanceof Map && sched.success == true)) {
            return [success: false, error: "Failed to update backup schedule: ${(sched instanceof Map) ? (sched.error ?: 'unknown') : 'unknown'}",
                    note: "Nothing was changed. Provide hour (0-23), minute (0-59), and the localBackupFrequency/cloudBackupFrequency you want; retry."]
        }
        scheduleUpdated = true
        if (args.scheduleOnly == true) {
            return [success: true, scheduleUpdated: true,
                    message: "Backup schedule updated; no immediate backup created (scheduleOnly=true).",
                    note: "Run hub_create_backup again without scheduleOnly to also create a backup now."]
        }
    }

    // The Write master is enforced centrally in executeTool; this tool creates the backup itself,
    // so it cannot require a pre-existing recent one (no requireDestructiveConfirm). A create-now
    // still needs confirm; a scheduleOnly call already returned above without needing it.
    if (!args.confirm) {
        throw new IllegalArgumentException("You must set confirm=true to create a backup (or pass scheduleOnly=true to only set the schedule).")
    }

    if (args.mock == true) {
        // Test-hub lever (maintainer-directed): stamp ONLY the destructive-confirm gate record
        // without performing any real backup -- the hub backup is a heavy operation the platform's
        // load limiter punishes, and e2e needs the GATED tools tested, not the backup itself.
        // Developer-mode-gated so a production client can't silently satisfy the gate with a lie.
        if (settings.enableDeveloperMode != true) {
            throw new IllegalArgumentException("hub_create_backup mock=true requires Developer Mode (it satisfies the destructive-confirm gate WITHOUT a real backup -- test environments only).")
        }
        def backupTime = now()
        state.lastBackupTimestamp = backupTime
        mcpLog("warn", "hub-admin", "MOCK backup recorded (no real backup performed; developer mode)")
        return [
            success: true,
            mocked: true,
            scheduleUpdated: scheduleUpdated,
            message: "MOCK backup recorded: the destructive-confirm gate is satisfied but NO real backup was created.",
            backupTimestamp: formatTimestamp(backupTime),
            backupTimestampEpoch: backupTime,
            note: "Test environments only. Create a real backup before relying on restore."
        ]
    }

    mcpLog("info", "hub-admin", "Creating hub backup (async trigger; the backup file is never downloaded through this app)...")

    try {
        // GET /hub/backupDB?fileName=latest makes the hub CREATE a fresh backup and stream the
        // .lzf back. The old implementation read that multi-MB binary through this app's
        // execution just to confirm success -- a one-off load spike the platform's per-app
        // limiter punishes with a STICKY device-dispatch block ~13 minutes later (verified A/B
        // on fw 2.5.0.157: every slurping backup wedged the hub; async-triggered backups never
        // did). So: fire the request asynchronously (the hub still creates the backup; the
        // async client's truncated body is discarded) and confirm completion via the hub's own
        // /hub/backup/statusJson instead of the binary response.
        def asyncParams = [uri: hubBaseUri(), path: "/hub/backupDB", query: [fileName: "latest"], timeout: 300]
        def cookie = getHubSecurityCookie()
        if (cookie) asyncParams.headers = [Cookie: cookie]
        asynchttpGet("backupResponseSink", asyncParams)

        // Confirm via statusJson: wait for an in-progress backup to finish (small JSON reads).
        // A small-DB backup can finish before the first poll, so backupInProgress=false is
        // treated as completion rather than requiring an observed true->false transition.
        def confirmed = false
        def statusUnreadable = false
        for (int i = 0; i < 20; i++) {
            pauseExecution(3000)
            def statusText = null
            try { statusText = hubInternalGet("/hub/backup/statusJson", null, 15) } catch (Exception ignored) { }
            def parsed = null
            try { parsed = statusText ? new groovy.json.JsonSlurper().parseText(statusText) : null } catch (Exception ignored) { }
            if (parsed instanceof Map && parsed.backupInProgress == false && parsed.cloudBackupInProgress != true) {
                confirmed = true
                break
            }
            if (parsed == null && i >= 2) { statusUnreadable = true; break }   // status endpoint unreadable; stop burning time
        }

        if (!confirmed) {
            // Do NOT satisfy the 24h destructive-confirm gate on an UNVERIFIED backup. Stamping
            // state.lastBackupTimestamp here would let destructive ops proceed believing a recovery
            // point exists when it may not (a rejected/in-progress/failed backup) -- a silent failure.
            // Leave the gate unstamped and report failure so the caller blocks until a backup is real.
            mcpLog("warn", "hub-admin", "Hub backup triggered but completion was NOT confirmed (${statusUnreadable ? '/hub/backup/statusJson unreadable' : 'still in progress after ~60s'}); destructive-confirm gate left unsatisfied")
            return [
                success: false,
                confirmed: false,
                scheduleUpdated: scheduleUpdated,
                error: statusUnreadable
                    ? "Hub backup was triggered but completion could not be confirmed (/hub/backup/statusJson was unreadable)."
                    : "Hub backup was triggered but did not confirm complete within ~60s (it may still be running).",
                note: "The 24h destructive-confirm gate was NOT satisfied -- do NOT run destructive ops yet. " +
                      "A large backup can still be completing; verify in the Hubitat UI (Settings -> Backup and Restore), " +
                      "or call hub_create_backup again once it finishes."
            ]
        }

        def backupTime = now()
        state.lastBackupTimestamp = backupTime
        mcpLog("info", "hub-admin", "Hub backup completed at ${formatTimestamp(backupTime)}")
        return [
            success: true,
            confirmed: true,
            scheduleUpdated: scheduleUpdated,
            message: "Hub backup created successfully",
            backupTimestamp: formatTimestamp(backupTime),
            backupTimestampEpoch: backupTime,
            note: "This backup is stored on the hub (Hubitat UI: Settings -> Backup and Restore)."
        ]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub backup FAILED", e)
        return [
            success: false,
            error: "Backup failed: ${e.message}",
            note: "The backup could not be created. Do NOT proceed with any Write master operations. " +
                  "Check Hub Security credentials if Hub Security is enabled, or try creating a backup manually from the Hubitat web UI."
        ]
    }
}

// asynchttpGet completion sink for the backup trigger: the .lzf body is deliberately never
// read into this app (see toolCreateHubBackup -- the whole point of the async trigger).
def backupResponseSink(response, data) {
    // The async /hub/backupDB response is the one place a rejected backup request surfaces. It
    // arrives AFTER toolCreateHubBackup returns (so it can't gate that call -- statusJson confirmation
    // does), but a non-2xx must NOT be swallowed at debug: log it loudly so a rejected backup leaves
    // a trace instead of silently looking fine.
    try {
        def st = response?.status
        if (st != null && !(st >= 200 && st < 300)) {
            mcpLog("warn", "hub-admin", "Hub backup async request returned non-2xx status=${st}; the backup may have been rejected -- verify before relying on it")
        } else {
            mcpLog("debug", "hub-admin", "backup async response status=${st}")
        }
    } catch (Exception ignored) { }
}

// POST /hub2/updateBackupSchedule {localBackupFrequency,cloudBackupFrequency,hour,minute,cloudBackupPassword}.
// Returns [success:true, schedule:<echo>] or [success:false, error:...]. Called by toolCreateHubBackup.
private _setHubBackupSchedule(Map schedule) {
    if (schedule == null) return [success: false, error: "schedule object is required"]
    if (!schedule.containsKey("hour") || !schedule.containsKey("minute")) {
        return [success: false, error: "schedule requires hour (0-23) and minute (0-59)"]
    }
    Integer hour, minute
    try {
        hour = schedule.hour as Integer
        minute = schedule.minute as Integer
    } catch (Exception e) {
        return [success: false, error: "hour and minute must be integers, got hour=${schedule.hour}, minute=${schedule.minute}"]
    }
    if (hour == null || hour < 0 || hour > 23) return [success: false, error: "hour must be 0-23, got: ${schedule.hour}"]
    if (minute == null || minute < 0 || minute > 59) return [success: false, error: "minute must be 0-59, got: ${schedule.minute}"]
    def body = [
        localBackupFrequency: schedule.localBackupFrequency,
        cloudBackupFrequency: schedule.cloudBackupFrequency,
        hour: hour,
        minute: minute,
        cloudBackupPassword: schedule.cloudBackupPassword ?: ""
    ]
    try {
        def parsed = hubInternalPostJson("/hub2/updateBackupSchedule", groovy.json.JsonOutput.toJson(body))
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, schedule: body]
        }
        return [success: false, error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response"]
    } catch (Exception e) {
        mcpLogError("hub-admin", "updateBackupSchedule failed", e)
        return [success: false, error: e.message]
    }
}

// Fetch + normalize the hub-DB backup lists (GET /hub2/localBackups, /hub2/cloudBackups). Used by
// toolListItemBackups when scope includes hub-DB. Returns [local: [...], cloud: [...], errors: [...]].
private _listHubBackups(boolean wantLocal, boolean wantCloud) {
    def out = [local: null, cloud: null, errors: []]
    if (wantLocal) {
        try {
            def raw = hubInternalGet("/hub2/localBackups")
            def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : []
            out.local = (parsed instanceof List) ? parsed.collect { [name: it.name, createTime: it.createTime, createTimeOrig: it.createTimeOrig, size: it.size] } : []
        } catch (Exception e) { mcpLogError("hub-admin", "list local hub backups failed", e); out.errors << "local: ${e.message}" }
    }
    if (wantCloud) {
        try {
            def raw = hubInternalGet("/hub2/cloudBackups", [force: false])
            def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : [:]
            def list = (parsed instanceof Map) ? (parsed.backups ?: []) : []
            out.cloud = list.collect { [path: it.path, createTime: it.createTime, hubVersion: it.hubVersion, hubName: it.hubName] }
        } catch (Exception e) { mcpLogError("hub-admin", "list cloud hub backups failed", e); out.errors << "cloud: ${e.message}" }
    }
    return out
}

// Hub-DB restore (GET /hub2/restoreLocalBackup?fileName= | /hub2/restoreCloudBackup?p=&t=). BOTH reboot
// the hub. Confirm-gated by the caller (toolRestoreItemBackup). location: "hub_local" | "hub_cloud".
private _restoreHubBackup(String location, Map args) {
    try {
        def parsed
        if (location == "hub_local") {
            if (!args.fileName) throw new IllegalArgumentException("scope=hub_local restore requires fileName (from hub_list_backups scope=hub_local)")
            def raw = hubInternalGet("/hub2/restoreLocalBackup", [fileName: args.fileName.toString()], 120)
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        } else {
            if (!args.cloudBackupPassword) throw new IllegalArgumentException("scope=hub_cloud restore requires cloudBackupPassword (the encryption password set on the cloud backup)")
            def raw = hubInternalGet("/hub2/restoreCloudBackup", [p: args.cloudBackupPassword.toString(), t: now()], 120)
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        }
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, type: "hub-db", location: location,
                    message: "Hub-DB restore accepted — the hub is rebooting now and will be unreachable for several minutes.",
                    note: "Re-check hub reachability after the reboot; the database has been replaced from the backup."]
        }
        return [success: false, type: "hub-db", location: location,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response",
                note: "Nothing was restored. Verify the backup exists with hub_list_backups."]
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub-DB restore failed", e)
        return [success: false, type: "hub-db", location: location, error: e.message, note: "Nothing was restored."]
    }
}

def toolDeleteHubBackup(args) {
    args = args ?: [:]
    requireDestructiveConfirm(args.confirm)
    def location = args.location
    if (!(location in ["local", "cloud"])) {
        throw new IllegalArgumentException("location must be 'local' or 'cloud'. For 'local' pass fileName; for 'cloud' pass path (both from hub_list_backups).")
    }
    try {
        def parsed
        if (location == "local") {
            if (!args.fileName) throw new IllegalArgumentException("location=local requires fileName (from hub_list_backups scope=hub_local)")
            def raw = hubInternalGet("/hub2/deleteLocalBackup", [fileName: args.fileName.toString()])
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        } else {
            if (!args.path) throw new IllegalArgumentException("location=cloud requires path (from hub_list_backups scope=hub_cloud)")
            def raw = hubInternalGet("/hub2/deleteCloudBackup", [path: args.path.toString()])
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        }
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, location: location, message: "Hub-DB ${location} backup deleted."]
        }
        return [success: false, location: location,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response",
                note: "Nothing was deleted. Verify the backup exists with hub_list_backups."]
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub-DB backup delete failed", e)
        return [success: false, location: location, error: e.message, note: "Nothing was deleted."]
    }
}

// scope=hub_uploaded: fetch an external .lzf from backupUrl, multipart-POST it to /hub2/uploadBackup,
// then GET /hub2/restoreUploadedBackup (reboots). The use case is migrating a backup from ANOTHER hub
// or restoring an archived off-hub file (if the backup is already on this hub, use hub_local/hub_cloud).
// OPEN-WORLD: it reaches the internet to fetch backupUrl. Confirm-gated by the caller.
// CAVEAT: the binary multipart is unit-tested for orchestration but NOT validated against a live hub
// (the destructive path is deliberately excluded from e2e); very large backups may strain the sandbox.
private _restoreUploadedBackup(Map args) {
    if (!args.backupUrl) throw new IllegalArgumentException("scope=hub_uploaded restore requires backupUrl (an http(s) URL to the .lzf backup to upload and restore)")
    def url = args.backupUrl.toString()
    if (!(url ==~ /(?i)^https?:\/\/.+/)) throw new IllegalArgumentException("backupUrl must be an http(s) URL, got: ${url}")
    byte[] fileBytes
    try {
        fileBytes = _fetchBytesFromUrl(url)
    } catch (Exception e) {
        return [success: false, type: "hub-db", location: "hub_uploaded", error: "Could not fetch the backup from backupUrl: ${e.message}",
                note: "Verify the URL is reachable from the hub and points at a .lzf backup file."]
    }
    if (fileBytes == null || fileBytes.length == 0) {
        return [success: false, type: "hub-db", location: "hub_uploaded", error: "Fetched 0 bytes from backupUrl.",
                note: "The URL returned an empty body; check it points at a real .lzf backup."]
    }
    // Cap the in-app multipart build: ByteArrayOutputStream is sandbox-blocked, so the body is
    // assembled via a Byte list -- fine for a typical backup, but a several-MB file becomes a
    // multi-million-element list that is slow/memory-heavy in the Groovy sandbox. Reject oversized
    // backups and point them at the Hubitat UI restore (the browser path itself caps at 15 MB).
    int maxUploadBytes = 8 * 1024 * 1024
    if (fileBytes.length > maxUploadBytes) {
        return [success: false, type: "hub-db", location: "hub_uploaded",
                error: "Backup is ${(fileBytes.length / (1024 * 1024)) as int} MB, over the ${maxUploadBytes / (1024 * 1024)} MB in-app upload limit.",
                note: "Restore a very large backup via the Hubitat UI (Settings -> Backup and Restore), not this tool."]
    }
    try {
        def up = _postMultipartBackup("/hub2/uploadBackup", "uploadFile", "uploaded.lzf", fileBytes)
        if (!(up instanceof Map && up.success == true)) {
            return [success: false, type: "hub-db", location: "hub_uploaded",
                    error: "Upload to the hub failed: ${(up instanceof Map) ? (up.message ?: up.error ?: 'hub rejected the upload') : 'unexpected response'}",
                    note: "Nothing was restored."]
        }
        def raw = hubInternalGet("/hub2/restoreUploadedBackup", null, 120)
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, type: "hub-db", location: "hub_uploaded",
                    message: "Uploaded backup accepted — the hub is rebooting now to restore it.",
                    note: "Re-check hub reachability after the reboot; the database is being replaced from the uploaded backup."]
        }
        return [success: false, type: "hub-db", location: "hub_uploaded",
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "restore did not report success") : "unexpected response",
                note: "The backup uploaded but the restore did not confirm — verify hub state."]
    } catch (Exception e) {
        mcpLogError("hub-admin", "uploaded-backup restore failed", e)
        return [success: false, type: "hub-db", location: "hub_uploaded", error: e.message, note: "Nothing was restored."]
    }
}

// Fetch raw bytes from an http(s) URL (the .lzf to upload). Binary fetch via httpGet with textParser off.
// Non-private so the Spock harness can stub it (Groovy dispatches private calls directly, bypassing metaClass).
def _fetchBytesFromUrl(String url) {
    byte[] out = null
    httpGet([uri: url, timeout: 120, textParser: false]) { resp ->
        def d = resp?.data
        if (d instanceof byte[]) out = d
        else if (d != null) out = d.bytes   // InputStream -> bytes
    }
    return out
}

// Build a multipart/form-data body for a single binary file part and POST it. Returns the parsed
// response Map (or a {success:<2xx>} fallback). Used only by _restoreUploadedBackup.
// Non-private so the Spock harness can stub it (Groovy dispatches private calls directly).
def _postMultipartBackup(String path, String field, String fileName, byte[] fileBytes) {
    def boundary = "----mcpBackupBoundary${now()}"
    byte[] pre = ("--${boundary}\r\nContent-Disposition: form-data; name=\"${field}\"; filename=\"${fileName}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toString().getBytes("UTF-8")
    byte[] post = ("\r\n--${boundary}--\r\n").toString().getBytes("UTF-8")
    // Concatenate the multipart parts WITHOUT naming a java.io stream class (sandbox-blocked,
    // SANDBOX-015): build a Byte list and coerce to byte[]. Adequate for typical backups; very
    // large ones are slow, but this path is niche (migration/DR) and unverified-live anyway.
    List parts = []
    parts.addAll(pre as List)
    parts.addAll(fileBytes as List)
    parts.addAll(post as List)
    byte[] body = parts as byte[]
    def params = [uri: hubBaseUri(), path: path, timeout: 300,
                  requestContentType: "multipart/form-data; boundary=${boundary}".toString(),
                  body: body]
    def cookie = getHubSecurityCookie()
    if (cookie) params.headers = [Cookie: cookie]
    def result = [success: false]
    httpPost(params) { resp ->
        def d = resp?.data
        if (d instanceof Map) { result = d }
        else {
            try { result = new groovy.json.JsonSlurper().parseText(d?.toString() ?: "{}") }
            catch (Exception ig) {
                // The caller REBOOTS the hub on success, so do NOT infer success from a 2xx alone --
                // require the documented {success:true} body. An HTML/login/empty 2xx is a failure.
                mcpLogError("hub-admin", "uploadBackup returned a non-JSON body (status=${resp?.status})", ig)
                result = [success: false, message: "Upload returned an unexpected (non-JSON) response; not treated as success."]
            }
        }
    }
    return result
}

def _getAllToolDefinitions_partItemBackups() {
    return [
        // ==================== Hub-DB (whole-hub) backup tools — issue #259 item #1 ====================
        [
            name: "hub_create_backup",
            description: """Create a full hub-database backup (whole-hub .lzf). REQUIRED before any Write master op (24h validity). Optionally set the automatic-backup schedule via `schedule` (scheduleOnly=true sets the schedule only). The only write tool needing no prior backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "true to create a backup now (omit with scheduleOnly)."],
                    mock: [type: "boolean", description: "Developer Mode only: stamp the 24h gate record; no real backup (test envs)."],
                    schedule: [type: "object", description: "Optional: set the automatic-backup schedule.", properties: [
                        hour: [type: "integer", description: "Hour 0-23"],
                        minute: [type: "integer", description: "Minute 0-59"],
                        localBackupFrequency: [description: "Local backup frequency (hub value)"],
                        cloudBackupFrequency: [description: "Cloud backup frequency (hub value)"],
                        cloudBackupPassword: [type: "string", description: "Cloud backup password"]
                    ]],
                    scheduleOnly: [type: "boolean", description: "With schedule: set schedule only, no backup now."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation succeeded"],
                    confirmed: [type: "boolean", description: "Whether backup completion was confirmed via the hub's backup status (false = best-effort trigger)"],
                    mocked: [type: "boolean", description: "true when mock=true stamped the gate record without a real backup"],
                    scheduleUpdated: [type: "boolean", description: "true when the automatic-backup schedule was set this call"],
                    message: [type: "string", description: "Human-readable result"],
                    backupTimestamp: [type: "string", description: "Formatted backup time"],
                    backupTimestampEpoch: [type: "integer", description: "Backup time in epoch millis"],
                    note: [type: "string", description: "Guidance / where to download the backup"],
                    error: [type: "string", description: "Failure detail"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_backup",
            description: """⚠️ Delete a whole-hub database backup (DESTRUCTIVE — removes a recovery point; tell the user first). location=local needs fileName, location=cloud needs path (both from hub_list_backups). Write master + confirm + a recent backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    location: [type: "string", enum: ["local", "cloud"], description: "Which store to delete from."],
                    fileName: [type: "string", description: "location=local: backup name from hub_list_backups."],
                    path: [type: "string", description: "location=cloud: backup path from hub_list_backups."],
                    confirm: [type: "boolean", description: "REQUIRED true. Confirms the delete."]
                ],
                required: ["location", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the delete succeeded"],
                    location: [type: "string", description: "local or cloud"],
                    message: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ],
        // ==================== Source-code item backup tools ====================
        [
            name: "hub_list_backups",
            description: "List backups: scope=source (default; auto-created code backups, each with a backupKey) | hub_local | hub_cloud | hub | all (whole-hub DB backups under hubLocalBackups/hubCloudBackups — name/path feed restore+delete). Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["source", "hub_local", "hub_cloud", "hub", "all"], description: "source (default) | hub_local | hub_cloud | hub | all."],
                    cursor: [type: "string", description: "Opt-in pagination cursor (source only); pass \"\" for the first page, iterate nextCursor."]
                ],
                required: []
            ],
            outputSchema: [
                type: "object",
                properties: [
                    backups: [type: "array", description: "Backup entries, newest first", items: [type: "object", properties: [
                        backupKey: [type: "string", description: "Restore key (e.g. app_123)"],
                        type: [type: "string", description: "app / driver / library / rm-rule"],
                        id: [type: "string", description: "Item ID"],
                        fileName: [type: "string", description: "Backup file in File Manager"],
                        timestampEpoch: [type: "integer", description: "Backup time (epoch ms)"],
                        timestamp: [type: "string", description: "Formatted backup time"],
                        age: [type: "string", description: "Human-readable age"],
                        sourceLength: [type: "integer", description: "Backed-up source length in chars"],
                        directDownload: [type: "string", description: "Local download URL"],
                        version: [description: "Item version (app/driver/library entries)"],
                        ruleId: [description: "Rule ID (rm-rule entries)"],
                        appLabel: [type: "string", description: "Rule label (rm-rule entries)"],
                        reason: [type: "string", description: "Snapshot reason (rm-rule entries)"]
                    ]]],
                    count: [type: "integer", description: "Backups returned"],
                    total: [type: "integer", description: "Total backups tracked"],
                    maxBackups: [type: "integer", description: "Max backups retained"],
                    storage: [type: "string", description: "Where backups are stored"],
                    howToRestore: [type: "string", description: "Restore guidance"],
                    manualRestore: [type: "string", description: "Manual restore guidance"],
                    message: [type: "string", description: "Present when no backups exist yet"],
                    nextCursor: [type: "string", description: "Present when more results remain"],
                    scope: [type: "string", description: "Echo of the requested scope (present for hub/all scopes)"],
                    hubLocalBackups: [type: "array", description: "Whole-hub local DB backups (scope hub_local/hub/all)", items: [type: "object", properties: [
                        name: [type: "string", description: "Backup file name — pass as fileName to hub_restore_backup/hub_delete_backup"],
                        createTime: [type: "string", description: "Creation time"],
                        size: [description: "Backup size in bytes"]
                    ]]],
                    hubCloudBackups: [type: "array", description: "Whole-hub cloud DB backups (scope hub_cloud/hub/all)", items: [type: "object", properties: [
                        path: [type: "string", description: "Cloud backup path — pass as path to hub_delete_backup"],
                        createTime: [type: "string", description: "Creation time"],
                        hubVersion: [type: "string", description: "Firmware version the backup was taken on"],
                        hubName: [type: "string", description: "Hub name the backup was taken from"]
                    ]]],
                    hubBackupErrors: [type: "array", description: "Per-source fetch errors, if any", items: [type: "string"]]
                ],
                required: []
            ]
        ],
        [
            name: "hub_get_backup",
            description: "Read the saved source code from one backup. Call hub_list_backups first to find the backupKey (e.g. 'app_123', 'driver_456', 'library_42'). Use this to inspect or diff a prior version before restoring. Large sources are omitted from the response (sourceTooLargeForResponse=true) with a File Manager download link instead. To re-apply a backup, use hub_restore_backup, not this tool. Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "The backup key from hub_list_backups (e.g., 'app_123', 'driver_456', or 'library_42')"]
                ],
                required: ["backupKey"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "Backup key read"],
                    type: [type: "string", description: "app / driver / library"],
                    id: [type: "string", description: "Item ID"],
                    fileName: [type: "string", description: "Backup file in File Manager"],
                    version: [description: "Item version"],
                    timestamp: [type: "string", description: "Formatted backup time"],
                    age: [type: "string", description: "Human-readable age"],
                    sourceLength: [type: "integer", description: "Source length in chars"],
                    directDownload: [type: "string", description: "Local download URL"],
                    source: [type: "string", description: "Backed-up source; present when small enough to inline"],
                    sourceTooLargeForResponse: [type: "boolean", description: "True when source omitted for size"],
                    manualDownload: [type: "string", description: "Download guidance; present when source omitted"],
                    howToRestore: [type: "string", description: "Restore guidance for this item type"],
                    message: [type: "string", description: "Note when source omitted for size"]
                ]
            ]
        ],
        [
            name: "hub_restore_backup",
            description: """⚠️ Restore a backup — tell the user first; hub-DB scopes REBOOT the hub. scope=source (default): an app/driver/rule by backupKey (deleted code → hub_create_*; deleted rules DO recreate). scope=hub_local/hub_cloud: restore the WHOLE hub DB (hub_local→fileName; hub_cloud→cloudBackupPassword). scope=hub_uploaded: upload an external .lzf from backupUrl, then restore (open-world). Write master + confirm.""",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["source", "hub_local", "hub_cloud", "hub_uploaded"], description: "source (default) | hub_local | hub_cloud | hub_uploaded."],
                    backupKey: [type: "string", description: "scope=source: backupKey from hub_list_backups (e.g. app_123)."],
                    fileName: [type: "string", description: "scope=hub_local: backup name from hub_list_backups."],
                    cloudBackupPassword: [type: "string", description: "scope=hub_cloud: cloud backup encryption password."],
                    backupUrl: [type: "string", description: "scope=hub_uploaded: http(s) URL to the .lzf to upload+restore."],
                    confirm: [type: "boolean", description: "REQUIRED true. Confirms the restore (hub-DB scopes reboot)."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the restore succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    type: [type: "string", description: "Item type restored (app/driver/rm-rule/visual-rule)"],
                    id: [description: "Item ID restored (code items)"],
                    restoredVersion: [description: "Version restored to (code items)"],
                    preRestoreBackup: [type: "string", description: "Backup key of the pre-restore snapshot (undo path)"],
                    preRestoreFile: [type: "string", description: "Pre-restore snapshot filename"],
                    undoHint: [type: "string", description: "How to undo this restore"],
                    backupKey: [type: "string", description: "Backup key (echoed on failure)"],
                    directDownload: [type: "string", description: "Local download URL (present on failure paths)"],
                    ruleId: [description: "Rule restores: the restored rule's app id (differs from the original when recreated)"],
                    originalRuleId: [description: "Rule restores: the rule id the snapshot was taken from"],
                    recreated: [type: "boolean", description: "Rule restores: true when the rule no longer existed and was recreated"],
                    verified: [type: "boolean", description: "visual-rule restores: whether a read-back confirmed the replayed definition"],
                    format: [type: "string", description: "visual-rule restores: 'classic' or 'graph'"],
                    settingsApplied: [type: "array", description: "rm-rule restores: settings replayed"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partItemBackups() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Apps/drivers (read)
        "hub_list_backups", "hub_get_backup"
    ]
}

def _idempotentWriteToolNames_partItemBackups() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Code (apps/drivers/libraries/bundles/backups)
        "hub_restore_backup",
        // Hub-DB: deleting a specific backup is retry-safe (a repeat is a no-op once it's gone).
        // hub_create_backup is deliberately NOT here -- each call makes a fresh backup.
        "hub_delete_backup"
    ]
}

def _openWorldToolNames_partItemBackups() {
    // hub_restore_backup can reach the internet (scope=hub_uploaded fetches backupUrl), so the whole
    // tool is open-world (the MCP openWorldHint is per-tool, not per-scope). Contributed to the app's
    // getOpenWorldToolNames() aggregator.
    return ["hub_restore_backup"]
}

def _toolDisplayMeta_partItemBackups() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_create_backup: [title: "Create Hub Backup", summary: "Create a whole-hub database backup (and optionally set the auto-backup schedule)."],
        hub_delete_backup: [title: "Delete Hub Backup", summary: "Delete a whole-hub database backup (local or cloud)."],
        hub_list_backups: [title: "List Backups", summary: "List source-code backups and (by scope) whole-hub database backups."],
        hub_get_backup: [title: "Get Code Backup", summary: "Read source code from a backup."],
        hub_restore_backup: [title: "Restore Backup", summary: "Restore a code/rule backup, or (by scope) the whole hub database."]
    ]
}
