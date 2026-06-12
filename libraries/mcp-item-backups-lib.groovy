library(name: "McpItemBackupsLib", namespace: "mcp", author: "kingpanther13", description: "Source-code backup tool implementations (hub_list_backups/hub_get_backup/hub_restore_backup) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListItemBackups(args = null) {
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

def _getAllToolDefinitions_partItemBackups() {
    return [
        // ==================== Item Backup Tools ====================
        [
            name: "hub_list_backups",
            description: "List auto-created source backups (apps, drivers, libraries, and RM rules) that the write tools snapshot before each modify/delete. Stored in the File Manager, newest first, max 20 retained. Use this to find a backupKey (e.g. 'app_123') to pass to hub_get_backup or hub_restore_backup. Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
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
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["backups", "count"]
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
            description: "⚠️ Restore app/driver to backed-up version. Tell user first. If a CODE item was DELETED, use hub_create_app/hub_create_driver/hub_create_library instead; rule snapshots (rm-rule backups, incl. Visual Rules) DO recreate a deleted rule. Library backups return a clear error directing you to hub_update_library. Requires Write master + confirm.",
            inputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "The backup key from hub_list_backups (e.g., 'app_123', 'driver_456', or 'library_42')"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the restore."]
                ],
                required: ["backupKey", "confirm"]
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
        "hub_restore_backup"
    ]
}

def _toolDisplayMeta_partItemBackups() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_list_backups: [title: "List Code Backups", summary: "List auto-created source-code backups."],
        hub_get_backup: [title: "Get Code Backup", summary: "Read source code from a backup."],
        hub_restore_backup: [title: "Restore Code Backup", summary: "Restore an app or driver to a backed-up version."]
    ]
}
