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
        // Fold the automatic-backup schedule in alongside the hub-DB backups. A failed schedule
        // read joins the existing hubBackupErrors / partial path rather than failing the listing.
        def sched = _readHubBackupSchedule()
        // Network-share settings (firmware 2.5.2+), which the hub serves only with full local
        // backups (it answers 403 without them; the UI reads them only then). Never a listing failure.
        if (!_hubFirmwareBefore("2.5.2")) {
            if (sched.ok && sched.schedule?.hasFullLocalBackup == false) {
                hubSections.networkBackup = [available: false, note: "Network-share backups come with the Full Local Backup subscription, which this hub does not have."]
            } else {
                def netCfg = _readNetworkBackupSettings()
                hubSections.networkBackup = netCfg.ok ? netCfg.settings : [error: netCfg.error]
            }
        }
        if (sched.ok) {
            hubSections.schedule = sched.schedule
        } else {
            hubSections.hubBackupErrors = (hubSections.hubBackupErrors ?: []) + [sched.error]
            hubSections.partial = true
        }
    }
    if (scope == "source") return _listSourceItemBackups(args)
    if (!(scope in ["source", "all"])) {
        return ([scope: scope] + hubSections + [note: "Whole-hub database backups. Restore via hub_restore_backup (scope=hub_local|hub_cloud); delete via hub_delete_backup."])
    }
    // scope == "all": source-code backups + the hub-DB sections in one response.
    return ([scope: scope] + _listSourceItemBackups(args) + hubSections)
}

private _listSourceItemBackups(args) {
    def manifest = _itemBackupManifest()

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
            deletePending: entry.deletePending == true,
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
    if (backupList.any { it.deletePending }) {
        result.deletePendingNote = "Entries with deletePending=true record an incomplete deletion. If the file is still readable, hub_get_backup or hub_restore_backup can clear the marker and recover the backup. Recover promptly: the next backup publication purges unprotected pending entries and attempts to delete their files, even if still readable. Pending entries cannot be reused as baselines."
    }
    if (cursor != null && paged.nextCursor != null) result.nextCursor = paged.nextCursor
    return result
}

// A pending-deletion marker is written before the delete, so a delete that failed
// twice or a reload between the two leaves the marker on a file that still exists.
// Probe before refusing: a present file clears the marker and serves as a backup.
// Probe and clear run under the monitor against a fresh view, so a deletion that
// lands in between cannot be resurrected. Only the marker is committed: no
// retention trim and no file deletion ride on a read path.
private Map _healPendingItemBackup(String backupKey, Map entry) {
    return _withBackupLock("heal ${backupKey}") {
        Map manifest = _itemBackupManifest()
        Map current = manifest[backupKey]
        if (!(current instanceof Map) || current.fileName?.toString() != entry.fileName?.toString()) {
            return [success: false, error: "The indexed backup changed; refresh hub_list_backups and retry."]
        }
        byte[] bytes = null
        try { bytes = downloadHubFile(current.fileName.toString()) }
        catch (Exception probeErr) {
            mcpLog("warn", "hub-admin", "Could not probe '${current.fileName}' while resolving its pending-deletion marker: ${probeErr.message}")
            return [success: false, error: "File verification failed: ${probeErr.message}. Check File Manager and retry."]
        }
        if (bytes == null || bytes.length == 0) {
            return [success: false, error: "The file is missing or empty. Check File Manager or choose another backup."]
        }
        current.remove("deletePending")
        try { _commitItemBackupManifest(manifest) }
        catch (Exception commitError) {
            mcpLog("warn", "hub-admin", "Backup '${backupKey}' is readable but its pending-deletion marker could not be cleared: ${commitError.message}")
            return [success: false, error: "The file is readable, but its metadata could not be updated: ${commitError.message}. Retry recovery."]
        }
        mcpLog("warn", "hub-admin", "Backup '${backupKey}' was marked pending deletion but its file '${current.fileName}' is still present; the marker was cleared")
        return [success: true]
    }
}

def toolGetItemBackup(args) {
    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123', 'driver_456', or 'library_42')")

    def manifest = _itemBackupManifest()
    def entry = manifest.get(args.backupKey)

    if (!entry) {
        mcpLog("debug", "hub-admin", "Backup key '${args.backupKey}' not found in manifest")
        def availableKeys = manifest.keySet().sort()
        return [
            error: "No backup found for key '${args.backupKey}'",
            availableBackups: availableKeys.isEmpty() ? "None -- no backups exist yet" : availableKeys.join(", "),
            hint: "Use 'hub_list_backups' to see all available backups with details"
        ]
    }
    Map recovery = entry.deletePending == true ? _healPendingItemBackup(args.backupKey.toString(), entry) : [success: true]
    if (recovery.success != true) {
        return [
            error: "Backup '${args.backupKey}' is marked pending deletion and its file '${entry.fileName}' could not be recovered. ${recovery.error}",
            backupKey: args.backupKey,
            hint: "Check File Manager and retry if the file is still present or the read failed temporarily. A readable file can recover this marker; otherwise choose a non-pending backup. Recover promptly: the next backup publication purges unprotected pending entries and attempts to delete their files, even if still readable."
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
    } else if (entry.type == "rm-rule") {
        result.howToRestore = "To restore this rule snapshot via MCP: call 'hub_restore_backup' with backupKey='${args.backupKey}' and confirm=true. The restore reapplies the saved rule configuration."
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
    return _toolRestoreSourceBackup(args)
}

private Map _toolRestoreSourceBackup(args) {
    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123', 'driver_456', 'library_42', or 'rm-rule_<id>_<ts>')")

    def entry
    def source
    Map ruleSnapshot = null
    String preRestoreBackupKey = null
    String preRestoreFileName = null
    boolean undoAvailable = false
    String undoWarning = null
    // Resolve the manifest entry, recover pending metadata, read its file, and
    // capture undo as one transaction. Release before source saves or rule replay:
    // self-restore can recompile this app, and rule wizards can take minutes.
    Map earlyResult = _withBackupLock("restore ${args.backupKey}") {
        def manifest = _itemBackupManifest()
        entry = manifest.get(args.backupKey)

        if (!entry) {
            mcpLog("debug", "hub-admin", "Restore: backup key '${args.backupKey}' not found in manifest")
            def availableKeys = manifest.keySet().sort()
            return [
                success: false,
                error: "No backup found for key '${args.backupKey}'",
                availableBackups: availableKeys.isEmpty() ? "None" : availableKeys.join(", ")
            ]
        }
        Map recovery = entry.deletePending == true ? _healPendingItemBackup(args.backupKey.toString(), entry) : [success: true]
        if (recovery.success != true) {
            return [
                success: false,
                error: "Backup '${args.backupKey}' is marked pending deletion and its file '${entry.fileName}' could not be recovered. ${recovery.error} Nothing was restored.",
                backupKey: args.backupKey,
                note: "Check File Manager and retry if the file is still present or the read failed temporarily. A readable file can recover this marker; otherwise choose a non-pending backup. Recover promptly: the next backup publication purges unprotected pending entries and attempts to delete their files, even if still readable."
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
                ruleSnapshot = _rmReadBackupSnapshot(entry)
                return null
            } catch (Exception e) {
                mcpLogError("hub-admin", "RM rule restore failed for key ${args.backupKey}", e)
                return [success: false, error: e.message, backupKey: args.backupKey, type: "rm-rule"]
            }
        }

        // Read the backup source from File Manager
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

        // Retention must protect both the selected restore target and its verified undo.
        preRestoreBackupKey = "prerestore_${entry.type}_${entry.id}"
        // Undoing an undo must keep the selected key retryable if the save fails.
        if (preRestoreBackupKey == args.backupKey.toString()) preRestoreBackupKey += "_undo"
        try {
            String restoreSourceHash = _mrtrSha256(source)
            def ajaxPath = (entry.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
            def responseText = hubInternalGet(ajaxPath, [id: entry.id])
            if (!responseText) throw new IllegalStateException("Current source fetch returned an empty response")
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            if (!(parsed instanceof Map) || !(parsed.source instanceof String) || !parsed.source) {
                throw new IllegalStateException("Current source fetch did not return source code")
            }
            if (parsed.source == source) {
                // A retry must not replace its original undo with already-restored
                // content. Missing undo is a warning when the source already matches.
                try {
                    def undo = _itemBackupManifest().get(preRestoreBackupKey)
                    if (!(undo instanceof Map) || undo.deletePending || undo.type != entry.type
                            || undo.id?.toString() != entry.id?.toString() || !undo.fileName
                            || undo.undoForBackupKey != args.backupKey.toString()
                            || undo.undoForSourceHash != restoreSourceHash || !undo.sourceHash) {
                        throw new IllegalStateException("Current source already matches this backup, but no matching pre-restore undo is recorded")
                    }
                    preRestoreFileName = undo.fileName.toString()
                    def undoBytes = downloadHubFile(preRestoreFileName)
                    if (undoBytes == null || _mrtrSha256(new String(undoBytes, "UTF-8")) != undo.sourceHash) {
                        throw new IllegalStateException("The recorded pre-restore undo file is missing or has changed")
                    }
                    undoAvailable = true
                    mcpLog("info", "hub-admin", "Current source already matches this backup -- preserving its verified pre-restore undo")
                } catch (Exception undoError) {
                    undoWarning = "No verified undo backup is available: ${undoError.message}. The live source already matches this backup; do not rely on an older pre-restore backup to undo it.".toString()
                    mcpLog("warn", "hub-admin", undoWarning)
                }
            } else {
                preRestoreFileName = _itemBackupFileName("mcp-prerestore-${entry.type}-${entry.id}.groovy")
                uploadHubFile(preRestoreFileName, parsed.source.getBytes("UTF-8"))
                String undoSourceHash = _mrtrSha256(parsed.source)
                try {
                    def undoBytes = downloadHubFile(preRestoreFileName)
                    if (undoBytes == null || _mrtrSha256(new String(undoBytes, "UTF-8")) != undoSourceHash) {
                        throw new IllegalStateException("Pre-restore backup upload could not be verified by reading the file back")
                    }
                } catch (Exception verificationError) {
                    try { deleteHubFile(preRestoreFileName) }
                    catch (Exception cleanupError) { mcpLog("error", "hub-admin", "Unverified undo file '${preRestoreFileName}' could not be removed: ${cleanupError.message}") }
                    throw verificationError
                }
                _publishUploadedItemBackup(preRestoreBackupKey, [
                    type: entry.type, id: entry.id, fileName: preRestoreFileName,
                    version: parsed.version, timestamp: now(), sourceLength: parsed.source.length(),
                    undoForBackupKey: args.backupKey.toString(), undoForSourceHash: restoreSourceHash,
                    sourceHash: undoSourceHash
                ], args.backupKey.toString())
                undoAvailable = true
                mcpLog("info", "hub-admin", "Pre-restore backup saved: ${preRestoreFileName} (version ${parsed.version}, ${parsed.source.length()} chars)")
            }
        } catch (Exception preBackupErr) {
            mcpLogError("hub-admin", "Pre-restore backup failed for ${entry.type} ${entry.id} (backupKey ${args.backupKey}); restore aborted", preBackupErr)
            return [
                success: false, undoAvailable: false,
                error: "Could not create pre-restore backup: ${preBackupErr.message}. Nothing was restored.",
                backupKey: args.backupKey,
                note: "The current source was left untouched, so nothing needs undoing. Confirm ${entry.type} ID ${entry.id} still exists and that File Manager accepts writes, then retry. To restore without an undo point, fetch the source with hub_get_backup and apply it with hub_update_app or hub_update_driver."
            ]
        }

        return null
    }
    if (earlyResult != null) return earlyResult
    if (ruleSnapshot != null) {
        try { return _rmRestoreFromBackup(entry, ruleSnapshot, args?.preserveRuleId != false, args.backupKey?.toString()) }
        catch (Exception e) {
            mcpLogError("hub-admin", "RM rule restore failed for key ${args.backupKey}", e)
            return [success: false, error: e.message, backupKey: args.backupKey, type: "rm-rule"]
        }
    }

    // Restoring the MCP server's OWN code drops the response exactly like a self-update
    // (the recompile kills the in-flight request), so it gets the same empty-body leniency
    // and lastSelfDeploy stash the update path has. Computed before the try so the
    // exception path can stash too.
    boolean isSelfRestore = false
    if (entry.type == "app") {
        def selfIds = [app?.id?.toString(), _resolveSelfAppClassId()?.toString()].findAll { it != null }
        isSelfRestore = selfIds.contains(entry.id?.toString())
    }

    // Now push the backup source directly via the hub internal API (bypass toolUpdateAppCode to avoid
    // its backupItemSource call which would overwrite our original backup file)
    try {
        // Fetch current version for optimistic locking
        def ajaxPath = (entry.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def versionResp = hubInternalGet(ajaxPath, [id: entry.id])
        def currentVersion = null
        if (versionResp) {
            try {
                def vParsed = new groovy.json.JsonSlurper().parseText(versionResp)
                currentVersion = vParsed.version
            } catch (Exception vErr) { /* proceed without version */ }
        }

        // Same JSON save endpoint the update path uses; id present => in-place update.
        def savePath = (entry.type == "app") ? "/app/saveOrUpdateJson" : "/driver/saveOrUpdateJson"

        def parsed = hubInternalPostJson(savePath, groovy.json.JsonOutput.toJson([
            id: entry.id as Integer,
            source: source,
            version: currentVersion ?: entry.version
        ]))

        def success = false
        def errorMsg = null
        if (parsed instanceof Map) {
            success = parsed.success == true
            if (success && parsed.id != null && parsed.id.toString() != entry.id.toString()) {
                success = false
                errorMsg = "Hub reported success but saved to ${entry.type} id ${parsed.id} instead of the targeted id ${entry.id} -- a duplicate code entry may have been created."
            } else if (!success) {
                errorMsg = parsed.message ?: parsed.errorMessage ?: "hub response lacked success=true: ${parsed.toString().take(200)}"
            }
        } else if (parsed == null) {
            // null is strictly an EMPTY body (non-JSON bodies arrive as the _unparseable sentinel
            // and fail above). Lenient only for a self-restore; otherwise fail closed.
            success = isSelfRestore
            if (!success) errorMsg = "Empty response from ${savePath} — restore may or may not have applied. Verify the ${entry.type} source before retrying."
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
            mcpLog("info", "hub-admin", "Restore succeeded: ${entry.type} ID ${entry.id} restored to version ${entry.version}")
            def restoreResult = [
                success: true,
                message: "Restored ${entry.type} ID ${entry.id} to version ${entry.version} (backup from ${formatTimestamp(entry.timestamp)})",
                type: entry.type,
                id: entry.id,
                restoredVersion: entry.version,
                undoAvailable: undoAvailable
            ]
            if (undoAvailable) {
                restoreResult.preRestoreBackup = preRestoreBackupKey
                restoreResult.preRestoreFile = preRestoreFileName
                restoreResult.undoHint = "To undo this restore, use 'hub_restore_backup' with backupKey='${preRestoreBackupKey}'"
            } else {
                restoreResult.warning = undoWarning
            }
            if (isSelfRestore && parsed == null) {
                restoreResult.assumed = true
                restoreResult.note = "This restored the MCP server's own code, so the hub's response was dropped by the recompile -- success is inferred, not hub-confirmed. Verify via hub_get_info (lastSelfDeploy) or hub_get_source."
            }
            return restoreResult
        } else {
            mcpLog("error", "hub-admin", "Restore failed for ${entry.type} ID ${entry.id}: ${errorMsg ?: 'unknown error'}")
            return [
                success: false,
                error: "Restore failed: ${errorMsg ?: 'unknown error'}",
                backupKey: args.backupKey,
                message: "Check the live source and confirm this backup is still available with hub_get_backup before retrying or restoring manually.",
                directDownload: "http://<HUB_IP>/local/${entry.fileName}"
            ]
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "Restore failed with exception for ${entry.type} ID ${entry.id}", e)
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
            message: "Check the live source and confirm this backup is still available with hub_get_backup before retrying or restoring manually.",
            directDownload: "http://<HUB_IP>/local/${entry.fileName}"
        ]
    }
}

// ==================== Hub-DB (whole-hub database) backup tools — issue #259 item #1 ====================
// These manage the WHOLE-HUB database backup (settings/devices/automations/state), a DIFFERENT domain
// from the source-code item backups above. Wire format reverse-engineered from resources/hub2-source
// (vue-hub2.min.js): list = GET /hub2/localBackups (array) and GET /hub2/cloudBackups?force= ({backups:[]});
// restore = GET /hub2/restoreLocalBackup?fileName= and GET /hub2/restoreCloudBackup?fileName=<path>&restorePassword=<pwd>&t=<ms>
// (BOTH reboot the hub); delete = GET /hub2/deleteLocalBackup?fileName= and GET /hub2/deleteCloudBackup?path=;
// schedule = POST /hub2/updateBackupSchedule. hub_uploaded and the full-restore paths multipart-upload the
// archive the way the browser does (_postMultipartBackup).

def toolCreateHubBackup(args) {
    args = args ?: [:]

    // cloudDownload copies an existing cloud backup into File Manager: its own mode, no backup made.
    if (args.cloudDownload != null) {
        if (args.schedule != null || args.networkBackup != null || args.testNetworkBackup == true || args.full == true) {
            throw new IllegalArgumentException("cloudDownload runs on its own; send schedule / networkBackup / full on a separate call.")
        }
        if (!args.confirm) throw new IllegalArgumentException("cloudDownload writes the backup to File Manager: pass confirm=true.")
        return _downloadCloudBackup(args.cloudDownload)
    }
    def schedulePresent = args.schedule != null
    def networkPresent = args.networkBackup != null
    def testNetwork = args.testNetworkBackup == true
    def scheduleOnly = args.scheduleOnly == true
    // Settings ride along on the same call: a `schedule` object (/hub2/updateBackupSchedule) and the
    // network-share settings. Only scheduleOnly WITH one of them skips creating a backup (and
    // confirm); every other shape creates one and needs confirm. Everything is validated before the
    // first write, so a refused call changes nothing.
    def willCreate = !(scheduleOnly && (schedulePresent || networkPresent || testNetwork))
    if (willCreate && !args.confirm) {
        throw new IllegalArgumentException("You must set confirm=true to create a backup (or pass scheduleOnly=true WITH schedule / networkBackup / testNetworkBackup to only change settings).")
    }
    if (willCreate && args.full == true && args.mock != true) {
        def unavailable = _fullBackupUnavailable()
        if (unavailable) return unavailable
    }
    def netCurrent = null
    if (networkPresent || testNetwork) {
        if (networkPresent) _validateNetworkBackupArgs(args.networkBackup)
        netCurrent = _readNetworkBackupSettings()
        if (!netCurrent.ok) {
            def out = [success: false, error: netCurrent.error, note: "Nothing was changed."]
            if (_hubFirmwareBefore("2.5.2")) out.note = "Nothing was changed. Network-share backups need firmware 2.5.2 or later."
            else if (_readHubBackupSchedule().schedule?.hasFullLocalBackup == false) out.note = "Nothing was changed. Network-share backups come with the Full Local Backup subscription, which this hub does not have."
            return out
        }
        if (networkPresent) {
            def spec = args.networkBackup
            boolean enabled = spec.containsKey("enabled") ? spec.enabled == true : netCurrent.raw.enabled == true
            def path = spec.containsKey("networkPath") ? spec.networkPath : netCurrent.raw.networkPath
            if (enabled && !path) throw new IllegalArgumentException("networkBackup.enabled=true needs networkPath (e.g. //nas/backups); the hub has none stored.")
        }
    }

    def scheduleUpdated = false
    if (schedulePresent) {
        def sched = _setHubBackupSchedule(args.schedule)
        if (!(sched instanceof Map && sched.success == true)) {
            return [success: false, error: "Failed to update backup schedule: ${(sched instanceof Map) ? (sched.error ?: 'unknown') : 'unknown'}",
                    note: "Nothing was changed. See the schedule field guidance and retry."]
        }
        scheduleUpdated = true
    }
    def networkResult = null
    if (networkPresent || testNetwork) {
        networkResult = _applyNetworkBackup(networkPresent ? args.networkBackup : null, testNetwork, netCurrent)
        if (networkResult.success != true) {
            // Say exactly what reached the hub: the schedule, the share settings, and no backup.
            def saved = []
            if (scheduleUpdated) saved << "the backup schedule"
            if (networkResult.settingsSaved == true) saved << "the network share settings"
            networkResult.scheduleUpdated = scheduleUpdated
            if (willCreate) networkResult.backupCreated = false
            networkResult.note = (saved ? "Saved: ${saved.join(' and ')}. " : "Nothing was saved. ") +
                                 (willCreate ? "No backup was created. " : "") + (networkResult.note ?: "")
            return networkResult
        }
    }
    if (!willCreate) {
        def out = [success: true, scheduleUpdated: scheduleUpdated,
                   message: "Backup settings updated; no backup created (scheduleOnly=true).",
                   note: "Run hub_create_backup again without scheduleOnly to also create a backup now."]
        if (networkResult) out.networkBackup = networkResult.networkBackup
        return out
    }
    if (args.full == true && args.mock != true) return _createFullLocalBackup(scheduleUpdated, networkResult?.networkBackup)

    // The Write master is enforced centrally in executeTool; this tool creates the backup itself, so
    // it cannot require a pre-existing recent one (no requireDestructiveConfirm). Confirm for the
    // create path was already validated above (before any schedule write).
    if (args.mock == true) {
        // Test-hub lever (maintainer-directed): stamp ONLY the destructive-confirm gate record
        // without performing any real backup -- the hub backup is a heavy operation the platform's
        // load limiter punishes, and e2e needs the GATED tools tested, not the backup itself.
        // Developer-mode-gated so a production client can't silently satisfy the gate with a lie.
        // mockEpoch stamps an arbitrary epoch (e.g. a deliberately STALE one) so e2e can drive
        // the gate's stale-stamp fallback path against the hub's real backup list.
        if (settings.enableDeveloperMode != true) {
            throw new IllegalArgumentException("hub_create_backup mock=true requires Developer Mode (it satisfies the destructive-confirm gate WITHOUT a real backup -- test environments only).")
        }
        def backupTime = (args.mockEpoch != null) ? (args.mockEpoch as Long) : now()
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
        // Pre-trigger snapshot of the hub's newest local backup: completion is confirmed by a
        // NEWER entry appearing in the hub's own list (ground truth), with statusJson quiet as
        // the fast-path signal. statusJson ALONE wedged real hubs (issue #361): a Hub Protect
        // cloud upload holds cloudBackupInProgress=true for minutes after the local .lzf is
        // already written, so a real, listed backup read as unconfirmed and the 24h gate never
        // stamped -- blocking every destructive tool despite a fresh recovery point.
        Long preEpoch = _latestLocalHubBackupEpoch("database")
        long confirmT0 = now()
        def asyncParams = [uri: hubBaseUri(), path: "/hub/backupDB", query: [fileName: "latest"], timeout: 300]
        def cookie = getHubSecurityCookie()
        if (cookie) asyncParams.headers = [Cookie: cookie]
        asynchttpGet("backupResponseSink", asyncParams)

        // Confirm via statusJson OR the backup list (small JSON reads). A small-DB backup can
        // finish before the first poll, so backupInProgress=false is treated as completion
        // rather than requiring an observed true->false transition. The loop is ALSO
        // wall-clock-capped: with slow reads the 20 iterations could otherwise stretch to
        // minutes of blocking (far past any transport ceiling) while pollers see "running".
        def confirmed = false
        def statusUnreadable = false
        int unreadableRounds = 0
        for (int i = 0; i < 20; i++) {
            pauseExecution(3000)
            def statusText = null
            try { statusText = hubInternalGet("/hub/backup/statusJson", null, 10) } catch (Exception ignored) { }
            def parsed = null
            try { parsed = statusText ? new groovy.json.JsonSlurper().parseText(statusText) : null } catch (Exception ignored) { }
            if (parsed instanceof Map && parsed.backupInProgress == false && parsed.cloudBackupInProgress != true) {
                confirmed = true
                break
            }
            // Ground truth: a local backup entry newer than the pre-trigger snapshot means the
            // backup file exists no matter what statusJson reports (an in-flight cloud upload
            // must not un-confirm an already-written local backup). When the pre-trigger read
            // failed, only an entry stamped since just before the trigger counts -- a stale
            // list must not confirm.
            Long newest = _latestLocalHubBackupEpoch("database")
            if (newest != null && newest > (preEpoch != null ? preEpoch : confirmT0 - 60000L)) {
                confirmed = true
                break
            }
            if (parsed == null && newest == null && ++unreadableRounds >= 3) { statusUnreadable = true; break }   // both signals unreadable; stop burning time
            if (now() - confirmT0 >= 57000L) break
        }

        if (!confirmed) {
            // Do NOT satisfy the 24h destructive-confirm gate on an UNVERIFIED backup. Stamping
            // state.lastBackupTimestamp here would let destructive ops proceed believing a recovery
            // point exists when it may not (a rejected/in-progress/failed backup) -- a silent failure.
            // Leave the gate unstamped and report failure so the caller blocks until a backup is real.
            mcpLog("warn", "hub-admin", "Hub backup triggered but completion was NOT confirmed (${statusUnreadable ? 'statusJson and the backup list were both unreadable' : 'no completion signal within ~60s'}); destructive-confirm gate left unsatisfied")
            return [
                success: false,
                confirmed: false,
                scheduleUpdated: scheduleUpdated,
                error: statusUnreadable
                    ? "Hub backup was triggered but completion could not be confirmed (/hub/backup/statusJson and the hub's backup list were both unreadable)."
                    : "Hub backup was triggered but did not confirm complete within ~60s (no new entry in the hub's local backup list yet; it may still be running).",
                note: "The 24h destructive-confirm gate was NOT satisfied -- do NOT run destructive ops yet. " +
                      "A large backup can still be completing; verify with hub_list_backups (scope=hub_local) or in the Hubitat UI (Settings -> Backup and Restore). " +
                      "Once the backup file exists, destructive tools accept it directly from the hub's backup list -- no re-run of hub_create_backup needed."
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

// Why a full local backup cannot be made here, checked before anything is written (null when it can
// be, or when the hub's backup data is unreadable and the request is sent anyway).
private Map _fullBackupUnavailable() {
    def why = null
    if (_hubFirmwareBefore("2.5.2")) {
        why = "Full local backups need firmware 2.5.2 or later."
    } else {
        def info = null
        try { info = _parseJsonOrNull(hubInternalGet("/hub2/backup/json")) }
        catch (Exception e) { mcpLog("warn", "hub-admin", "full backup pre-check: /hub2/backup/json unreadable (${e.message}); requesting the backup anyway") }
        if (info instanceof Map && info.hasFullLocalBackup == false) why = "This hub does not offer full local backups (hasFullLocalBackup is false)."
    }
    if (why == null) return null
    return [success: false, full: true, scheduleUpdated: false, error: why,
            note: "Full local backups need firmware 2.5.2 and the Full Local Backup subscription (hub_get_info(includeSubscriptions=true)). Nothing was changed; hub_create_backup without full makes a database backup."]
}

// asynchttpGet completion sink for the backup triggers (/hub/backupDB and /hub2/createFullLocalBackup):
// the archive body is never read into this app; the new entry in the hub's backup list confirms it.
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
    // READ-MERGE from GET /hub2/backup/json so omitted fields keep their current value -- the
    // POST is wholesale-shaped (it carries localBackupFrequency/cloudBackupFrequency/hour/minute),
    // so sending a partial set would blank the rest. Field names differ across the two endpoints:
    // read = databaseCleanupTimeHour/databaseCleanupJobMinute; write = hour/minute.
    def cur = [:]
    try {
        def raw = hubInternalGet("/hub2/backup/json")
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        cur = (parsed instanceof Map) ? parsed : [:]
    } catch (Exception e) {
        mcpLogError("hub-admin", "could not read current backup schedule", e)
        return [success: false, error: "Could not read the current backup schedule (/hub2/backup/json): ${e.message}", note: "Nothing was changed."]
    }
    def hourIn = schedule.containsKey("hour") ? schedule.hour : cur.databaseCleanupTimeHour
    def minuteIn = schedule.containsKey("minute") ? schedule.minute : cur.databaseCleanupJobMinute
    Integer hour, minute
    try {
        hour = hourIn as Integer
        minute = minuteIn as Integer
    } catch (Exception e) {
        return [success: false, error: "hour and minute must be integers, got hour=${hourIn}, minute=${minuteIn}"]
    }
    if (hour == null || hour < 0 || hour > 23) return [success: false, error: "hour must be 0-23, got: ${hourIn}"]
    if (minute == null || minute < 0 || minute > 59) return [success: false, error: "minute must be 0-59, got: ${minuteIn}"]
    def localFreq = schedule.containsKey("localBackupFrequency") ? schedule.localBackupFrequency : cur.localBackupFrequency
    def cloudFreq = schedule.containsKey("cloudBackupFrequency") ? schedule.cloudBackupFrequency : cur.cloudBackupFrequency
    // The cloud backup PASSWORD is the one field /hub2/backup/json does NOT expose (it reads back
    // null), so we cannot read-merge it. If cloud backup is enabled now OR would be after this change
    // and the caller did not supply cloudBackupPassword, REFUSE -- a wholesale write would blank the
    // password and silently disable cloud backups. (Pass cloudBackupFrequency=0 to turn cloud off.)
    int cloudNow = ((cur.cloudBackupFrequency ?: 0) as Integer)
    int cloudAfter = ((cloudFreq ?: 0) as Integer)
    if ((cloudNow > 0 || cloudAfter > 0) && !schedule.containsKey("cloudBackupPassword")) {
        return [success: false, error: "Cloud backup is enabled but cloudBackupPassword was not provided.",
                note: "Changing the schedule replaces the cloud-backup password, which the hub does not expose for read-back. Pass cloudBackupPassword (the current cloud-backup encryption password) to keep cloud backups working, or pass cloudBackupFrequency=0 to turn cloud backup off."]
    }
    def body = [
        localBackupFrequency: localFreq,
        cloudBackupFrequency: cloudFreq,
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

private Map _readHubBackupSchedule() {
    // Read the current automatic-backup schedule from GET /hub2/backup/json -- the same source
    // _setHubBackupSchedule read-merges before a write. Field names differ from the write endpoint
    // (read = databaseCleanupTimeHour/databaseCleanupJobMinute; write = hour/minute). The cloud-backup
    // password (backupPassword) is deliberately NEVER returned -- it is a secret (the endpoint returns
    // it as null anyway). Returns [ok:true, schedule:[...]] on success, or [ok:false, error:...] when
    // the read OR a non-numeric frequency/time field fails -- so hub_list_backups can fold the schedule
    // into the hub-DB listing (and route the failure through its existing hubBackupErrors/partial path)
    // instead of failing the whole call.
    def cur
    try {
        def raw = hubInternalGet("/hub2/backup/json")
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        if (!(parsed instanceof Map)) {
            return [ok: false, error: "schedule: the hub did not return a readable backup schedule (/hub2/backup/json)"]
        }
        cur = parsed
    } catch (Exception e) {
        mcpLogError("hub-admin", "could not read current backup schedule", e)
        return [ok: false, error: "schedule: could not read the backup schedule (/hub2/backup/json): ${e.message}"]
    }
    // Convert the numeric fields inside the error-handled path: a non-numeric value from the hub must
    // surface as ok:false (-> hubBackupErrors/partial), never throw out and abort the whole listing.
    try {
        Integer localFreq = (cur.localBackupFrequency != null) ? (cur.localBackupFrequency as Integer) : null
        Integer cloudFreq = (cur.cloudBackupFrequency != null) ? (cur.cloudBackupFrequency as Integer) : null
        return [ok: true, schedule: [
            localBackupFrequency: localFreq,
            cloudBackupFrequency: cloudFreq,
            hour: (cur.databaseCleanupTimeHour != null) ? (cur.databaseCleanupTimeHour as Integer) : null,
            minute: (cur.databaseCleanupJobMinute != null) ? (cur.databaseCleanupJobMinute as Integer) : null,
            localBackupEnabled: (localFreq != null) ? (localFreq > 0) : null,
            cloudBackupEnabled: (cloudFreq != null) ? (cloudFreq > 0) : null,
            hasCloudBackupEntitlements: cur.hasCloudBackupEntitlements,
            hasCloudRestoreEntitlements: cur.hasCloudRestoreEntitlements,
            // Firmware 2.5.2: full local backups (database + File Manager files + radio data).
            hasFullLocalBackup: cur.hasFullLocalBackup,
            fullLocalBackupSupported: cur.fullLocalBackupSupported,
            fileManagerBackupExcludedCount: cur.fileManagerBackupExcludedCount,
            lastCloudBackupMessage: cur.lastCloudBackupMessage,
            lastNetworkBackupMessage: cur.lastNetworkBackupMessage,
            zwaveJsEnabled: cur.zwaveJsEnabled,
            zigbeeDisabled: cur.zigbeeDisabled,
            zwaveDisabled: cur.zwaveDisabled,
            note: "Frequencies are in DAYS (0=off). hour/minute is the daily backup time. The cloud-backup password is never returned. Change the schedule via hub_create_backup(schedule=...)."
        ]]
    } catch (Exception e) {
        mcpLogError("hub-admin", "backup schedule has a non-numeric field", e)
        return [ok: false, error: "schedule: the hub returned a non-numeric schedule field (/hub2/backup/json): ${e.message}"]
    }
}

// Network-share backup settings (firmware 2.5.2): GET /hub2/networkBackup/settings answers
// {enabled, networkPath, username, password}. `raw` (with the password) is only for the read-merge
// write; `settings` is what a response carries, with passwordSet instead of the password.
private Map _readNetworkBackupSettings() {
    try {
        def raw = hubInternalGet("/hub2/networkBackup/settings")
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        if (!(parsed instanceof Map)) return [ok: false, error: "networkBackup: the hub did not return readable settings (/hub2/networkBackup/settings)"]
        return [ok: true, raw: parsed, settings: [enabled: parsed.enabled == true, networkPath: parsed.networkPath ?: null,
                                                   username: parsed.username ?: null, passwordSet: (parsed.password ? true : false)]]
    } catch (Exception e) {
        return [ok: false, error: "networkBackup: could not read the network backup settings (/hub2/networkBackup/settings): ${e.message}"]
    }
}

private void _validateNetworkBackupArgs(spec) {
    if (!(spec instanceof Map) || spec.isEmpty()) {
        throw new IllegalArgumentException("networkBackup must be an object with at least one of enabled, networkPath, username, password.")
    }
    def known = ["enabled", "networkPath", "username", "password"]
    def unknown = spec.keySet().findAll { !(it in known) }
    if (unknown) throw new IllegalArgumentException("Unknown networkBackup field(s): ${unknown.join(', ')}. Valid: ${known.join(', ')}.")
    if (spec.containsKey("enabled") && !(spec.enabled instanceof Boolean)) throw new IllegalArgumentException("networkBackup.enabled must be true or false.")
}

// Read-merges the network-share settings (an omitted field keeps its value, the password included),
// POSTs them to /hub2/networkBackup/settings, and/or POSTs /hub2/networkBackup/test, as the UI does.
private Map _applyNetworkBackup(Map spec, boolean test, Map cur) {
    def merged = [enabled: cur.raw.enabled == true, networkPath: cur.raw.networkPath ?: "", username: cur.raw.username ?: "", password: cur.raw.password ?: ""]
    if (spec != null) {
        if (spec.containsKey("enabled")) merged.enabled = (spec.enabled == true)
        if (spec.containsKey("networkPath")) merged.networkPath = (spec.networkPath ?: "").toString()
        if (spec.containsKey("username")) merged.username = (spec.username ?: "").toString()
        if (spec.containsKey("password")) merged.password = (spec.password ?: "").toString()
    }
    def out = [success: true]
    try {
        if (spec != null) {
            def r = hubInternalPostJson("/hub2/networkBackup/settings", groovy.json.JsonOutput.toJson(merged))
            if (!(r instanceof Map) || r.success != true) {
                return [success: false, error: "The hub did not save the network backup settings: ${(r instanceof Map) ? (r.message ?: 'no reason given') : r}"]
            }
            out.settingsSaved = true
        }
        if (test) {
            def t = hubInternalPostJson("/hub2/networkBackup/test", groovy.json.JsonOutput.toJson(merged))
            out.test = [success: (t instanceof Map && t.success == true), message: (t instanceof Map) ? t.message : t?.toString()]
            if (!out.test.success) {
                out.success = false
                out.error = "Network share test failed: ${out.test.message ?: 'no reason given'}"
                out.note = "Fix the share path or credentials and test again."
            }
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "network backup settings/test failed", e)
        return [success: false, error: "Network backup request failed: ${e.message}", settingsSaved: out.settingsSaved == true,
                note: "Check the share path and credentials, then read the settings with hub_list_backups(scope='hub_local')."]
    }
    out.networkBackup = [enabled: merged.enabled, networkPath: merged.networkPath ?: null, username: merged.username ?: null, passwordSet: merged.password ? true : false]
    if (out.test) out.networkBackup.test = out.test
    return out
}

// Full local backup (.tar.gz: database, File Manager files up to 100 MiB, Zigbee and Z-Wave data):
// GET /hub2/createFullLocalBackup, the create-and-download request the 2.5.2 UI sends on the LAN
// (/hub2/createLocalBackup?full=true is the Remote Admin form and 404s locally). Fired asynchronously
// like /hub/backupDB, so the archive never loads into the app, and confirmed by a newer full entry.
private Map _createFullLocalBackup(boolean scheduleUpdated, networkBackup) {
    Long pre = _latestLocalHubBackupEpoch("full")
    long t0 = now()
    def params = [uri: hubBaseUri(), path: "/hub2/createFullLocalBackup", timeout: 600]
    def cookie = getHubSecurityCookie()
    if (cookie) params.headers = [Cookie: cookie]
    asynchttpGet("backupResponseSink", params)
    boolean confirmed = false
    for (int i = 0; i < 15 && now() - t0 < 57000L; i++) {
        pauseExecution(4000)
        Long newest = _latestLocalHubBackupEpoch("full")
        if (newest != null && newest > (pre != null ? pre : t0 - 60000L)) { confirmed = true; break }
    }
    def out = [full: true, scheduleUpdated: scheduleUpdated]
    if (networkBackup) out.networkBackup = networkBackup
    if (!confirmed) {
        return out + [success: false, confirmed: false,
                error: "The full backup was requested but no new full backup appeared within ~60s.",
                note: "A full backup with many File Manager files can take minutes. Check hub_list_backups(scope='hub_local') for a fullBackup:true entry before relying on it; a hub refusal shows in hub_get_logs(mode='mcp'). The 24h destructive-confirm gate was not stamped."]
    }
    def stamp = now()
    state.lastBackupTimestamp = stamp
    return out + [success: true, confirmed: true, message: "Full local backup created (database, File Manager files, and radio data).",
            backupTimestamp: formatTimestamp(stamp), backupTimestampEpoch: stamp,
            note: "Listed with fullBackup:true in hub_list_backups(scope='hub_local'); restore it with hub_restore_backup(scope='hub_local', fileName=..., fullRestore={...})."]
}

// Copies a cloud backup into File Manager: POST /hub2/downloadCloudDatabaseBackup (the .lzf database)
// or /hub2/downloadCloudFilesBackup (the File Manager archive) with {fileName: <cloud path>, password}.
private Map _downloadCloudBackup(spec) {
    if (!(spec instanceof Map) || !spec.path || !spec.cloudBackupPassword) {
        throw new IllegalArgumentException("cloudDownload needs {path, cloudBackupPassword, part?}: path from hub_list_backups(scope='hub_cloud'), part 'database' (default) or 'files'.")
    }
    def part = (spec.part ?: "database").toString()
    if (!(part in ["database", "files"])) throw new IllegalArgumentException("cloudDownload.part must be 'database' or 'files'.")
    String ext = (part == "files") ? ".tar.gz" : ".lzf"
    String name = "cloud-backup-${part}-${new Date(now()).format('yyyyMMdd-HHmmss')}${ext}"
    // The whole copy is held in memory, so it is sized first from the cloud list's total.
    def total = _cloudBackupSize(spec.path.toString())
    if (total.size == null || total.size > 16L * 1024 * 1024) {
        return [success: false, cloudDownload: true,
                error: (total.size == null) ? _sizeLookupError(total.reason, spec.path.toString(), "cloud") :
                                              "That cloud backup is ${(total.size / (1024 * 1024)) as long} MB, over the 16 MB in-app limit.",
                note: "Check the path with hub_list_backups(scope='hub_cloud'), or download it from Settings > Backup and Restore in the Hubitat web UI. Nothing was saved."]
    }
    try {
        def got = hubInternalBytes("POST", (part == "files") ? "/hub2/downloadCloudFilesBackup" : "/hub2/downloadCloudDatabaseBackup", null,
                                   [fileName: spec.path.toString(), password: spec.cloudBackupPassword.toString()])
        byte[] bytes = got.bytes
        if (!bytes || bytes.length == 0) {
            return [success: false, cloudDownload: true, error: "The hub returned no backup for that cloud path${got.error ? ': ' + got.error : '.'}",
                    note: "Check the path and the cloud backup password (hub_list_backups(scope='hub_cloud')). Nothing was saved."]
        }
        if (!((part == "files") ? _isGzip(bytes) : _isH2Database(bytes))) {
            return [success: false, cloudDownload: true, error: "The hub's reply is not a ${part} backup: ${_bytesPreview(bytes)}",
                    note: "Check the path and the cloud backup password. Nothing was saved."]
        }
        uploadHubFile(name, bytes)
        return [success: true, cloudDownload: true, part: part, fileName: name, sizeBytes: bytes.length,
                message: "Cloud backup ${part} saved to File Manager as ${name}.",
                note: "Download it from http://<HUB_IP>/local/${name}."]
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "cloud backup download failed", e)
        return [success: false, cloudDownload: true, error: "Cloud backup download failed: ${e.message}",
                note: "Check the path and the cloud backup password (hub_list_backups(scope='hub_cloud'))."]
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
            // fullBackup entries (.tar.gz: database + File Manager + radio data) are listed with the
            // database-only .lzf ones but restore only through Hubitat's full-restore flow.
            out.local = (parsed instanceof List) ? parsed.collect {
                [name: it.name, createTime: it.createTime, createTimeOrig: it.createTimeOrig, size: it.fileSize != null ? it.fileSize : it.size,
                 fullBackup: it.fullBackup == true, hasZWave: it.hasZWave == true, hasZigbee: it.hasZigbee == true,
                 platformVersion: it.platformVersion]
            } : []
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

// A backup's size from the hub's own list: [size: bytes] or [reason: unreadable | notListed | sizeUnknown].
private Map _localBackupSize(String fileName) {
    def parsed
    try {
        parsed = _parseJsonOrNull(hubInternalGet("/hub2/localBackups"))
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "local backup list unreadable (${e.message})")
    }
    if (!(parsed instanceof List)) return [reason: "unreadable"]
    def entry = parsed.find { it instanceof Map && it.name?.toString() == fileName }
    if (entry == null) return [reason: "notListed"]
    Long size = _parseSizeBytes(entry.fileSize ?: entry.size)
    return (size != null) ? [size: size] : [reason: "sizeUnknown"]
}

// The cloud list gives each backup's total size: an upper bound for either part of it.
private Map _cloudBackupSize(String path) {
    def parsed
    try {
        parsed = _parseJsonOrNull(hubInternalGet("/hub2/cloudBackups", [force: false]))
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "cloud backup list unreadable (${e.message})")
    }
    if (!(parsed instanceof Map) || !(parsed.backups instanceof List)) return [reason: "unreadable"]
    def entry = parsed.backups.find { it instanceof Map && it.path?.toString() == path }
    if (entry == null) return [reason: "notListed"]
    Long size = _parseSizeBytes(entry.fileSize)
    return (size != null) ? [size: size] : [reason: "sizeUnknown"]
}

private String _sizeLookupError(String reason, String name, String where) {
    if (reason == "notListed") return "No backup '${name}' is in the hub's ${where} backup list.".toString()
    if (reason == "unreadable") return "The hub's ${where} backup list could not be read, so the size of '${name}' is unknown and it was not downloaded.".toString()
    return "The hub's ${where} backup list gives no size for '${name}', so it was not downloaded.".toString()
}

// "7 MB" / "512 KB" / a bare byte count -> bytes; null when it is not one of those.
private Long _parseSizeBytes(value) {
    def m = value?.toString() =~ /(?i)^\s*([\d.]+)\s*(B|KB|MB|GB)?\s*$/
    if (!m.find()) return null
    def mult = [B: 1L, KB: 1024L, MB: 1024L * 1024, GB: 1024L * 1024 * 1024].get((m.group(2) ?: "B").toUpperCase())
    return ((m.group(1) as BigDecimal) * mult) as Long
}

// A full local backup as the hub lists it (fullBackup:true); the .tar.gz name decides when the list
// is unreadable or does not list the file.
private boolean _isFullLocalHubBackup(String fileName) {
    try {
        def raw = hubInternalGet("/hub2/localBackups")
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        def entry = (parsed instanceof List) ? parsed.find { it instanceof Map && it.name?.toString() == fileName } : null
        if (entry != null) return entry.fullBackup == true
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "_isFullLocalHubBackup: local backup list unreadable (${e.message}); deciding from the file name")
    }
    return fileName.toLowerCase().endsWith(".tar.gz")
}

// fullRestore options for a full (.tar.gz) backup: what to restore beyond the database. Validated
// before anything is fetched or sent.
private Map _fullRestoreOptions(opts) {
    if (opts != null && !(opts instanceof Map)) throw new IllegalArgumentException("fullRestore must be an object: {restoreZigbee, restoreZwave, restoreFiles, deleteExistingFiles, allowZwaveFirmwareMismatch} (all booleans, default false).")
    def known = ["restoreZigbee", "restoreZwave", "restoreFiles", "deleteExistingFiles", "allowZwaveFirmwareMismatch"]
    def m = (opts ?: [:]) as Map
    def unknown = m.keySet().findAll { !(it in known) }
    if (unknown) throw new IllegalArgumentException("Unknown fullRestore field(s): ${unknown.join(', ')}. Valid: ${known.join(', ')}.")
    def notBool = m.findAll { k, v -> v != null && !(v instanceof Boolean) }.keySet()
    if (notBool) throw new IllegalArgumentException("fullRestore field(s) ${notBool.join(', ')} must be true or false.")
    if (m.deleteExistingFiles == true && m.restoreFiles != true) throw new IllegalArgumentException("fullRestore.deleteExistingFiles applies only with restoreFiles=true.")
    return known.collectEntries { k -> [(k): m.get(k) == true] }
}

// Full restore as the 2.5.2 UI runs it: multipart-upload the .tar.gz to /hub2/uploadFullLocalBackup,
// then GET /hub2/restoreFullLocalBackup with the restore choices. The hub reboots on success.
private Map _restoreFullFromBytes(String location, byte[] bytes, String fileName, Map opts) {
    if (!_isGzip(bytes)) {
        return [success: false, type: "hub-full", location: location, error: "That is not a full backup archive (.tar.gz): ${_bytesPreview(bytes)}", note: "Nothing was restored."]
    }
    int maxBytes = 16 * 1024 * 1024
    if (bytes.length > maxBytes) {
        return [success: false, type: "hub-full", location: location,
                error: "The full backup is ${(bytes.length / (1024 * 1024)) as int} MB, over the 16 MB in-app limit.",
                note: "Restore a large full backup from Settings > Backup and Restore in the Hubitat web UI (it accepts up to 150 MB). Nothing was restored."]
    }
    def up
    try {
        up = _postMultipartBackup("/hub2/uploadFullLocalBackup", "uploadFile", fileName, bytes)
    } catch (Exception e) {
        mcpLogError("hub-admin", "full backup upload failed", e)
        return [success: false, type: "hub-full", location: location, error: "Upload of the full backup failed: ${e.message}", note: "Nothing was restored."]
    }
    if (!(up instanceof Map) || up.success != true) {
        return [success: false, type: "hub-full", location: location,
                error: "Upload of the full backup failed: ${(up instanceof Map) ? (up.message ?: up.error ?: 'hub rejected the upload') : 'unexpected response'}",
                note: "Nothing was restored."]
    }
    def r
    try {
        def q = [suppressZWaveFirmwareMismatchHubNewer: opts.allowZwaveFirmwareMismatch, restoreZb: opts.restoreZigbee, restoreZw: opts.restoreZwave,
                 restoreFiles: opts.restoreFiles, deleteExistingFiles: opts.deleteExistingFiles, t: now()]
        r = _parseJsonOrNull(hubInternalGet("/hub2/restoreFullLocalBackup", q, 120))
    } catch (Exception e) {
        mcpLogError("hub-admin", "full backup restore request failed", e)
        return _restoreRequestFailed("hub-full", location, e)
    }
    if (r instanceof Map && r.success == true) {
        return [success: true, type: "hub-full", location: location, restored: [database: true, zigbee: opts.restoreZigbee, zwave: opts.restoreZwave, files: opts.restoreFiles],
                message: "Full restore accepted — the hub is rebooting now and will be unreachable for several minutes.",
                note: "Re-check hub reachability after the reboot."]
    }
    def out = [success: false, type: "hub-full", location: location, response: r]
    if (r instanceof Map && r.zwaveFirmwareMismatchHubNewer) {
        out.error = "The hub's Z-Wave radio firmware is newer than the backup's."
        out.note = "Nothing was restored. Retry with fullRestore.allowZwaveFirmwareMismatch=true to restore anyway, or leave restoreZwave false."
    } else if (r instanceof Map && r.zwaveFirmwareMismatchBackupNewer) {
        out.error = "The backup's Z-Wave radio firmware is newer than the hub's."
        out.note = "Nothing was restored. Update the hub's Z-Wave radio firmware first, or restore with restoreZwave false."
    } else if (r instanceof Map && r.zwaveStackMismatch) {
        out.error = r.zwaveStackMismatchMessage ?: "The backup was made on a different Z-Wave stack (${r.backupZWaveStack}) than the hub runs (${r.activeZWaveStack})."
        out.note = "Nothing was restored. Switch the Z-Wave stack with hub_set_zwave(zwave_js=...) first, or restore with restoreZwave false."
    } else {
        out.error = (r instanceof Map) ? (r.message ?: "the hub did not report success") : "unexpected response"
        out.note = "The backup uploaded but the restore did not confirm — verify hub state."
    }
    return out
}

// A restore request that threw. An HTTP status means the hub answered and refused it; without one
// (a timeout or dropped connection) the hub may already be rebooting into the restore.
private Map _restoreRequestFailed(String type, String location, Exception e) {
    if (_httpStatusOf(e) != null) {
        return [success: false, type: type, location: location, error: "The hub refused the restore: ${e.message}", note: "Nothing was restored."]
    }
    return [success: false, type: type, location: location, outcome: "unknown", error: "The restore request got no answer: ${e.message}",
            note: "The hub may be rebooting into the restore. Do not resend: wait a few minutes, then check hub_get_info (uptime) before deciding."]
}

private _parseJsonOrNull(String raw) {
    if (!raw) return null
    try { return new groovy.json.JsonSlurper().parseText(raw) } catch (Exception e) { return null }
}

// A full backup on this hub: the UI can only download and re-upload it, so do exactly that. The
// listed size ("7 MB", whole megabytes) is checked first; the cap after the download catches rounding.
private Map _restoreLocalFullBackup(String fileName, Map opts) {
    def listed = _localBackupSize(fileName)
    if (listed.size == null) {
        return [success: false, type: "hub-full", location: "hub_local", error: _sizeLookupError(listed.reason, fileName, "local"),
                note: "Nothing was restored. Check the name with hub_list_backups(scope='hub_local'), or restore it from Settings > Backup and Restore in the Hubitat web UI."]
    }
    if (listed.size > 16L * 1024 * 1024) {
        return [success: false, type: "hub-full", location: "hub_local", error: "The full backup is ${(listed.size / (1024 * 1024)) as long} MB, over the 16 MB in-app limit.",
                note: "Restore a large full backup from Settings > Backup and Restore in the Hubitat web UI (it accepts up to 150 MB). Nothing was restored."]
    }
    def got
    try {
        got = hubInternalBytes("GET", "/hub2/downloadLocalBackup", [fileName: fileName])
    } catch (Exception e) {
        return [success: false, type: "hub-full", location: "hub_local", error: "Could not read the full backup '${fileName}': ${e.message}", note: "Nothing was restored."]
    }
    byte[] bytes = got.bytes
    if (!bytes || bytes.length == 0) {
        return [success: false, type: "hub-full", location: "hub_local", error: "The hub returned no backup for '${fileName}'${got.error ? ': ' + got.error : '.'}", note: "Nothing was restored."]
    }
    return _restoreFullFromBytes("hub_local", bytes, fileName, opts)
}

// Hub-DB restore. BOTH reboot the hub. Confirm-gated by the caller (toolRestoreItemBackup). A full
// local backup goes to the download/upload/restoreFullLocalBackup flow instead (_restoreLocalFullBackup).
// Wire format verified against vue-hub2.min.js:
//   local: GET /hub2/restoreLocalBackup?fileName=<name>
//   cloud: GET /hub2/restoreCloudBackup?fileName=<path>&restorePassword=<pwd>&restoreZb=&restoreZw=&restoreFiles=&deleteExistingFiles=&t=<ms>
// Cloud restores the DATABASE only by default (radios/files NOT restored) -- the safe minimal default.
private _restoreHubBackup(String location, Map args) {
    String path
    Map query
    if (location == "hub_local") {
        if (!args.fileName) throw new IllegalArgumentException("scope=hub_local restore requires fileName (from hub_list_backups scope=hub_local)")
        def fullOpts = (args.fullRestore != null) ? _fullRestoreOptions(args.fullRestore) : null
        if (_isFullLocalHubBackup(args.fileName.toString())) {
            // A full backup never goes to the database restore: route it through the full-restore flow.
            return _restoreLocalFullBackup(args.fileName.toString(), fullOpts ?: _fullRestoreOptions(null))
        }
        if (fullOpts != null) throw new IllegalArgumentException("fullRestore applies only to a full backup (fullBackup:true in hub_list_backups); '${args.fileName}' is a database backup.")
        path = "/hub2/restoreLocalBackup"
        query = [fileName: args.fileName.toString()]
    } else {
        if (!args.path) throw new IllegalArgumentException("scope=hub_cloud restore requires path (the cloud backup's `path` from hub_list_backups scope=hub_cloud)")
        if (!args.cloudBackupPassword) throw new IllegalArgumentException("scope=hub_cloud restore requires cloudBackupPassword (the encryption password set on the cloud backup)")
        if (args.fullRestore != null) throw new IllegalArgumentException("fullRestore does not apply to scope=hub_cloud: a cloud restore brings back the database only.")
        path = "/hub2/restoreCloudBackup"
        query = [
            fileName: args.path.toString(),                       // the cloud backup id is its `path`
            restorePassword: args.cloudBackupPassword.toString(),
            restoreZb: false, restoreZw: false, restoreFiles: false, deleteExistingFiles: false,
            suppressZWaveFirmwareMismatchHubNewer: false,
            t: now()
        ]
    }
    def parsed
    try {
        parsed = _parseJsonOrNull(hubInternalGet(path, query, 120))
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub-DB restore request failed", e)
        return _restoreRequestFailed("hub-db", location, e)
    }
    if (parsed instanceof Map && parsed.success == true) {
        return [success: true, type: "hub-db", location: location,
                message: "Hub-DB restore accepted — the hub is rebooting now and will be unreachable for several minutes.",
                note: "Re-check hub reachability after the reboot; the database has been replaced from the backup."]
    }
    return [success: false, type: "hub-db", location: location,
            error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response",
            note: (parsed instanceof Map) ? "Nothing was restored. Verify the backup exists with hub_list_backups." :
                                            "The hub's answer was not readable; check hub_get_info (uptime) before retrying."]
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

// scope=hub_uploaded: fetch a backup from backupUrl and restore it. The fetched bytes decide the
// route: a gzip archive is a full backup (multipart to /hub2/uploadFullLocalBackup, then
// restoreFullLocalBackup); an H2 database (.lzf) goes to /hub2/uploadBackup + /hub2/restoreUploadedBackup.
// Both reboot. The use case is migrating a backup from ANOTHER hub or restoring an archived off-hub
// file (if the backup is already on this hub, use hub_local/hub_cloud). OPEN-WORLD: it fetches
// backupUrl. Confirm-gated by the caller. The multipart path is unit-tested, not run on a live hub.
private _restoreUploadedBackup(Map args) {
    if (!args.backupUrl) throw new IllegalArgumentException("scope=hub_uploaded restore requires backupUrl (an http(s) URL to the .lzf database backup or the .tar.gz full backup to upload and restore)")
    def url = args.backupUrl.toString()
    if (!(url ==~ /(?i)^https?:\/\/.+/)) throw new IllegalArgumentException("backupUrl must be an http(s) URL, got: ${url}")
    String urlPath = url.tokenize("?")[0]
    boolean lzfUrl = urlPath.toLowerCase().endsWith(".lzf")
    if (args.fullRestore != null && lzfUrl) {
        throw new IllegalArgumentException("fullRestore applies only to a full .tar.gz backup; backupUrl points at a .lzf database backup.")
    }
    def opts = (args.fullRestore != null) ? _fullRestoreOptions(args.fullRestore) : null
    // Size it first when the host says (8 MB for a .lzf, 16 MB otherwise); a host that ignores the
    // range request sends the whole body, which is then used instead of fetching it again.
    long probeCap = (lzfUrl ? 8L : 16L) * 1024 * 1024
    def probe = _probeUrl(url, probeCap)
    if (probe.size != null && probe.size > probeCap) {
        return [success: false, type: lzfUrl ? "hub-db" : "hub-full", location: "hub_uploaded",
                error: "The backup at backupUrl is ${(probe.size / (1024 * 1024)) as long} MB, over the ${probeCap / (1024 * 1024)} MB in-app limit.",
                note: "Restore a large backup from Settings > Backup and Restore in the Hubitat web UI. Nothing was restored."]
    }
    byte[] fileBytes = probe.bytes
    if (fileBytes == null) {
        try {
            fileBytes = _fetchBytesFromUrl(url)
        } catch (Exception e) {
            return [success: false, type: "hub-uploaded", location: "hub_uploaded", error: "Could not fetch the backup from backupUrl: ${e.message}",
                    note: "Nothing was restored. Verify the URL is reachable from the hub."]
        }
    }
    if (fileBytes == null || fileBytes.length == 0) {
        return [success: false, type: "hub-uploaded", location: "hub_uploaded", error: "Fetched 0 bytes from backupUrl.", note: "Nothing was restored."]
    }
    if (_isGzip(fileBytes)) {
        // The file name is the URL path's last segment (never the host of a path-less URL).
        def segs = urlPath.replaceFirst(/(?i)^https?:\/\/[^\/]*/, "").tokenize("/")
        String seg = segs ? segs[-1] : null
        return _restoreFullFromBytes("hub_uploaded", fileBytes, (seg && seg.contains(".")) ? seg : "full-backup.tar.gz", opts ?: _fullRestoreOptions(null))
    }
    if (opts != null) {
        return [success: false, type: "hub-full", location: "hub_uploaded", error: "fullRestore needs a full backup archive (.tar.gz); backupUrl serves something else: ${_bytesPreview(fileBytes)}",
                note: "Nothing was restored."]
    }
    if (!_isH2Database(fileBytes)) {
        return [success: false, type: "hub-db", location: "hub_uploaded", error: "backupUrl does not serve a hub backup (.lzf database or .tar.gz full backup): ${_bytesPreview(fileBytes)}",
                note: "Nothing was restored."]
    }
    // The multipart body is built in memory; the browser path itself caps database backups at 15 MB.
    int maxUploadBytes = 8 * 1024 * 1024
    if (fileBytes.length > maxUploadBytes) {
        return [success: false, type: "hub-db", location: "hub_uploaded",
                error: "Backup is ${(fileBytes.length / (1024 * 1024)) as int} MB, over the ${maxUploadBytes / (1024 * 1024)} MB in-app upload limit.",
                note: "Restore a very large backup via the Hubitat UI (Settings -> Backup and Restore), not this tool. Nothing was restored."]
    }
    def up
    try {
        up = _postMultipartBackup("/hub2/uploadBackup", "uploadFile", "uploaded.lzf", fileBytes)
    } catch (Exception e) {
        mcpLogError("hub-admin", "uploaded-backup upload failed", e)
        return [success: false, type: "hub-db", location: "hub_uploaded", error: "Upload to the hub failed: ${e.message}", note: "Nothing was restored."]
    }
    if (!(up instanceof Map && up.success == true)) {
        return [success: false, type: "hub-db", location: "hub_uploaded",
                error: "Upload to the hub failed: ${(up instanceof Map) ? (up.message ?: up.error ?: 'hub rejected the upload') : 'unexpected response'}",
                note: "Nothing was restored."]
    }
    def parsed
    try {
        parsed = _parseJsonOrNull(hubInternalGet("/hub2/restoreUploadedBackup", null, 120))
    } catch (Exception e) {
        mcpLogError("hub-admin", "uploaded-backup restore request failed", e)
        return _restoreRequestFailed("hub-db", "hub_uploaded", e)
    }
    if (parsed instanceof Map && parsed.success == true) {
        return [success: true, type: "hub-db", location: "hub_uploaded",
                message: "Uploaded backup accepted — the hub is rebooting now to restore it.",
                note: "Re-check hub reachability after the reboot; the database is being replaced from the uploaded backup."]
    }
    return [success: false, type: "hub-db", location: "hub_uploaded",
            error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "restore did not report success") : "unexpected response",
            note: "The backup uploaded but the restore did not confirm — verify hub state."]
}

def _getAllToolDefinitions_partItemBackups() {
    return [
        // ==================== Hub-DB (whole-hub) backup tools — issue #259 item #1 ====================
        [
            name: "hub_create_backup",
            description: """Create a hub-database backup. Destructive tools require one within 24h.[[FLAT_TRIM]] Whole-hub .lzf, or with full=true a full local backup (.tar.gz: database, File Manager files, Zigbee and Z-Wave data). Optionally set the automatic-backup schedule (`schedule`) and the network-share backup (`networkBackup`, `testNetworkBackup`); scheduleOnly=true changes those settings only. cloudDownload copies an existing cloud backup into File Manager instead. The only write tool needing no prior backup.[[/FLAT_TRIM]]
[[FLAT_TRIM]]
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "true to create a backup now (omit with scheduleOnly)."],
                    mock: [type: "boolean", description: "Developer Mode only: stamp the 24h gate record; no real backup (test envs)."],
                    mockEpoch: [type: "integer", description: "Developer Mode only, with mock=true: stamp this epoch-millis instead of now (test envs; lets tests set a stale gate record)."],
                    schedule: [type: "object", description: "Optional: set the automatic-backup schedule. Omitted fields keep their current value (read-merged from the hub).[[FLAT_TRIM]] If cloud backup is enabled you MUST pass cloudBackupPassword (the hub doesn't expose it for read-back) or pass cloudBackupFrequency=0 to turn cloud backup off.[[/FLAT_TRIM]]", properties: [
                        hour: [type: "integer", description: "[[FLAT_TRIM]]Hour 0-23 (kept if omitted)[[/FLAT_TRIM]]"],
                        minute: [type: "integer", description: "[[FLAT_TRIM]]Minute 0-59 (kept if omitted)[[/FLAT_TRIM]]"],
                        localBackupFrequency: [type: "integer", enum: [0, 1, 2, 3, 5, 7, 14, 21, 28], description: "[[FLAT_TRIM]]Local backup interval in DAYS (0=off); kept if omitted[[/FLAT_TRIM]]"],
                        cloudBackupFrequency: [type: "integer", enum: [0, 1, 2, 3, 5, 7, 14, 21, 28], description: "[[FLAT_TRIM]]Cloud backup interval in DAYS (0=off); kept if omitted[[/FLAT_TRIM]]"],
                        cloudBackupPassword: [type: "string", description: "[[FLAT_TRIM]]Cloud-backup encryption password. Required when cloud backup is/stays enabled.[[/FLAT_TRIM]]"]
                    ]],
                    scheduleOnly: [type: "boolean", description: "With schedule/networkBackup: settings only, no backup now."],
                    full: [type: "boolean", description: "[[FLAT_TRIM]]Full local backup (database + files + radio data). Needs the Full Local Backup subscription.[[/FLAT_TRIM]]"],
                    networkBackup: [type: "object", description: "[[FLAT_TRIM]]Network-share backup: {enabled?, networkPath?, username?, password?}; omitted fields keep their value.[[/FLAT_TRIM]]"],
                    testNetworkBackup: [type: "boolean", description: "[[FLAT_TRIM]]Test the network share.[[/FLAT_TRIM]]"],
                    cloudDownload: [type: "object", description: "[[FLAT_TRIM]]Copy a cloud backup into File Manager: {path, cloudBackupPassword, part? (database|files)}; send alone.[[/FLAT_TRIM]]"],
                    args: [type: "object"]
                ]
            ]
        ],
        [
            name: "hub_delete_backup",
            description: """⚠️ Delete a whole-hub database backup (DESTRUCTIVE; tell the user first). Write master + confirm + a recent backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    location: [type: "string", enum: ["local", "cloud"], description: "Which store to delete from."],
                    fileName: [type: "string", description: "location=local: backup name from hub_list_backups."],
                    path: [type: "string", description: "location=cloud: backup path from hub_list_backups."],
                    confirm: [type: "boolean", description: "REQUIRED true. Confirms the delete."]
                ],
                required: ["location", "confirm"]
            ]
        ],
        // ==================== Source-code item backup tools ====================
        [
            name: "hub_list_backups",
            description: "List backups: scope=source (default; auto-created code backups, each with a backupKey) | hub_local | hub_cloud | hub | all. Read-only. The hub scopes also return the automatic-backup `schedule` and the `networkBackup` share settings.[[FLAT_TRIM]] Whole-hub DB backups come back under hubLocalBackups/hubCloudBackups — a local backup's name and a cloud backup's path feed hub_restore_backup/hub_delete_backup; fullBackup:true marks a full local backup (database + files + radio data). The `schedule` block carries the frequencies (days) + daily hour/minute and the full-backup / last-backup status fields; no password is ever returned. A failed schedule read joins hubBackupErrors (partial:true) and a failed share read stays inside networkBackup; neither fails the listing. See hub_get_tool_guide(section='backup').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["source", "hub_local", "hub_cloud", "hub", "all"], description: "Which backups to list; default source."],
                    cursor: [type: "string", description: "Opt-in pagination cursor (source only); pass \"\" for the first page, iterate nextCursor."]
                ],
                required: []
            ]
        ],
        [
            name: "hub_get_backup",
            description: "Read the saved source code from one backup. Call hub_list_backups first to find the backupKey.[[FLAT_TRIM]] Use this to inspect or diff a prior version before restoring; to re-apply it use hub_restore_backup, not this tool. Large sources are omitted from the response (sourceTooLargeForResponse=true) with a File Manager download link instead.[[/FLAT_TRIM]] Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "The backup key from hub_list_backups (e.g., 'app_123', 'driver_456', or 'library_42')"]
                ],
                required: ["backupKey"]
            ]
        ],
        [
            name: "hub_restore_backup",
            description: """⚠️ Restore a backup — tell the user first; hub-DB scopes REBOOT the hub.[[FLAT_TRIM]] scope=source (default): an app/driver/rule by backupKey (deleted code → hub_create_*; deleted rules DO recreate; a rule restores in place by default; preserveRuleId:false restores a Rule Machine backup that has an App Cloner export as an exact copy with a NEW id -- read ruleId and restoredVia from the result). scope=hub_local/hub_cloud: restore the WHOLE hub DB (hub_local→fileName; hub_cloud→path+cloudBackupPassword); a full local backup (fullBackup:true) runs the full-restore flow with fullRestore choosing radios/files. scope=hub_uploaded: upload an external .lzf (or a full .tar.gz) from backupUrl, then restore (open-world).[[/FLAT_TRIM]] Write master + confirm.
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying.[[FLAT_TRIM]] See hub_get_tool_guide(section='slow_ops').[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["source", "hub_local", "hub_cloud", "hub_uploaded"], description: "Default source."],
                    backupKey: [type: "string", description: "scope=source: backupKey from hub_list_backups (e.g. app_123)."],
                    fileName: [type: "string", description: "scope=hub_local: backup name from hub_list_backups."],
                    path: [type: "string", description: "scope=hub_cloud: `path` from hub_list_backups."],
                    cloudBackupPassword: [type: "string", description: "scope=hub_cloud: cloud backup encryption password."],
                    backupUrl: [type: "string", description: "scope=hub_uploaded: http(s) URL of the .lzf or full .tar.gz."],
                    fullRestore: [type: "object", description: "[[FLAT_TRIM]]Full backups: {restoreZigbee?, restoreZwave?, restoreFiles?, deleteExistingFiles?, allowZwaveFirmwareMismatch?} (default false).[[/FLAT_TRIM]]"],
                    preserveRuleId: [type: "boolean", description: "Keep the rule id (default true).[[FLAT_TRIM]] true: restore an RM/native app backup in place by settings replay; a replay empties settings the app gained after the backup (settingsCleared). false: restore a Rule Machine backup that carries an App Cloner export as an exact copy with a NEW id, deleting the old rule once the copy matches the backup.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED true. Confirms the restore (hub-DB scopes reboot)."],
                ],
                required: ["confirm"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partItemBackups() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Apps/drivers (read); hub_list_backups also folds in the hub-DB schedule read
        "hub_list_backups", "hub_get_backup"
    ]
}

def _idempotentWriteToolNames_partItemBackups() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // hub_restore_backup is not here: its hub scopes reboot the hub on every call.
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
        hub_create_backup: [title: "Create Hub Backup", summary: "Create a database or full local backup, set backup schedule and network share, or copy a cloud backup."],
        hub_delete_backup: [title: "Delete Hub Backup", summary: "Delete a whole-hub database backup (local or cloud)."],
        hub_list_backups: [title: "List Backups", summary: "List code backups and (by scope) hub database/full backups plus the backup schedule and network share."],
        hub_get_backup: [title: "Get Code Backup", summary: "Read source code from a backup."],
        hub_restore_backup: [title: "Restore Backup", summary: "Restore a code/rule backup, or (by scope) the whole hub database."]
    ]
}
