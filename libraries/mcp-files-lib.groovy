library(name: "McpFilesLib", namespace: "mcp", author: "kingpanther13", description: "File Manager tool implementations (hub_list_files/hub_read_file/hub_write_file/hub_delete_file) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListFiles(args = null) {
    mcpLog("debug", "file-manager", "Listing files in File Manager")
    def cursor = args?.cursor

    // Try known File Manager API endpoints (varies by firmware version)
    def endpoints = ["/hub/fileManager/json", "/hub/fileManager"]
    def responseText = null
    def endpointUsed = null

    for (endpoint in endpoints) {
        try {
            responseText = hubInternalGet(endpoint)
            if (responseText) {
                endpointUsed = endpoint
                mcpLog("debug", "file-manager", "Got response from ${endpoint} (${responseText.length()} chars): ${responseText.take(300)}")
                break
            }
        } catch (Exception e) {
            mcpLog("debug", "file-manager", "Endpoint ${endpoint} failed: ${e.message}")
        }
    }

    if (!responseText) {
        return [
            files: [],
            total: 0,
            message: "File Manager API not available on this firmware. Use Hubitat > Settings > File Manager to view files.",
            manualAccess: "Go to Hubitat > Settings > File Manager to view files in the web UI."
        ]
    }

    try {
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        def fileList = []

        if (parsed instanceof List) {
            // Direct list response: [{name: "file.txt", size: 123}, ...]
            fileList = parsed.collect { f ->
                def name = (f instanceof Map) ? (f.name ?: f.toString()) : f.toString()
                def entry = [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                if (f instanceof Map) {
                    if (f.size != null) entry.size = f.size
                    if (f.date) entry.lastModified = f.date
                }
                return entry
            }
        } else if (parsed instanceof Map) {
            // Object response: {files: [...]} or {type: [...]}
            def files = parsed.files ?: parsed.values()?.flatten()
            if (files instanceof List) {
                fileList = files.collect { f ->
                    def name = (f instanceof Map) ? (f.name ?: f.toString()) : f.toString()
                    def entry = [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                    if (f instanceof Map) {
                        if (f.size != null) entry.size = f.size
                        if (f.date) entry.lastModified = f.date
                    }
                    return entry
                }
            }
        }

        fileList = fileList.sort { a, b -> (a.name <=> b.name) }

        // Shape-drift diagnostic: a non-empty parsed response that yielded zero files
        // is almost always a firmware shape change (new top-level key, files nested
        // deeper, etc.). Surface so a "no files" report isn't silently misread.
        // instanceof-based labelling because .class is unreliable in the sandbox.
        if (fileList.isEmpty() && parsed) {
            def shapeHint = (parsed instanceof Map) ? "Map with keys=${parsed.keySet()?.take(10)?.toList()}" :
                            (parsed instanceof List) ? "empty List" : "non-map non-list"
            mcpLog("warn", "file-manager", "hub_list_files: parsed response yielded zero files (${shapeHint}) -- shape may not be recognized", null, [details: [endpoint: endpointUsed, shape: shapeHint]])
        }

        mcpLog("info", "file-manager", "Listed ${fileList.size()} files in File Manager (via ${endpointUsed})")
        def pagedFM = _paginateList(fileList, cursor, 100, "hub_list_files")
        def res = [
            files: pagedFM.page,
            total: fileList.size(),
            storage: "Files are stored locally on the hub's file system. Access via http://<HUB_IP>/local/<filename> or Hubitat > Settings > File Manager."
        ]
        if (cursor != null && pagedFM.nextCursor != null) res.nextCursor = pagedFM.nextCursor
        return res
    } catch (Exception jsonErr) {
        // Response wasn't JSON — might be HTML File Manager page
        mcpLog("debug", "file-manager", "Response from ${endpointUsed} was not JSON: ${jsonErr.message}")

        // Try to extract file names from HTML response
        def fileList = []
        try {
            def matcher = responseText =~ /(?i)href=["']?\/local\/([^"'\s>]+)/
            while (matcher.find()) {
                def name = java.net.URLDecoder.decode(matcher.group(1), "UTF-8")
                if (!fileList.any { it.name == name }) {
                    fileList << [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                }
            }
        } catch (Exception htmlErr) {
            mcpLog("debug", "file-manager", "HTML parsing also failed: ${htmlErr.message}")
        }

        if (fileList) {
            fileList = fileList.sort { a, b -> (a.name <=> b.name) }
            mcpLog("info", "file-manager", "Listed ${fileList.size()} files from File Manager HTML page")
            def pagedHtml = _paginateList(fileList, cursor, 100, "hub_list_files")
            def res = [
                files: pagedHtml.page,
                total: fileList.size(),
                note: "File list extracted from File Manager HTML page. Sizes not available. Use Hubitat > Settings > File Manager for full details.",
                storage: "Files are stored locally on the hub's file system. Access via http://<HUB_IP>/local/<filename> or Hubitat > Settings > File Manager."
            ]
            if (cursor != null && pagedHtml.nextCursor != null) res.nextCursor = pagedHtml.nextCursor
            return res
        }

        return [
            files: [],
            total: 0,
            error: "Could not parse File Manager response. The API format may have changed.",
            rawResponsePreview: responseText.take(500),
            manualAccess: "Go to Hubitat > Settings > File Manager to view files in the web UI."
        ]
    }
}

def toolReadFile(args) {
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")

    def maxChunkSize = 60000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

    mcpLog("debug", "file-manager", "Reading file: ${args.fileName} (offset: ${requestedOffset}, length: ${requestedLength})")
    def content
    try {
        def bytes = downloadHubFile(args.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        content = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLogError("file-manager", "Failed to read file '${args.fileName}'", e)
        return [
            success: false,
            error: "File '${args.fileName}' could not be read: ${e.message}",
            suggestion: "Check that the file name is correct. Go to Hubitat > Settings > File Manager to see available files.",
            directDownload: "http://<HUB_IP>/local/${args.fileName}"
        ]
    }

    def totalLength = content.length()
    def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
    def chunk = (requestedOffset < totalLength) ? content.substring(requestedOffset, endIndex) : ""
    def hasMore = endIndex < totalLength

    def result = [
        success: true,
        fileName: args.fileName,
        totalLength: totalLength,
        offset: requestedOffset,
        chunkLength: chunk.length(),
        hasMore: hasMore,
        content: chunk,
        directDownload: "http://<HUB_IP>/local/${args.fileName}"
    ]
    if (hasMore) {
        result.nextOffset = endIndex
        result.remainingChars = totalLength - endIndex
        result.hint = "Call again with offset: ${endIndex} to get the next chunk."
    }

    mcpLog("info", "file-manager", "Read file '${args.fileName}' (${totalLength} chars total, returned offset ${requestedOffset}..${endIndex}${hasMore ? ', more available' : ''})")
    return result
}

def toolWriteFile(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")
    if (args.content == null) throw new IllegalArgumentException("content is required")

    // Validate file name — only A-Za-z0-9, hyphens, underscores, periods allowed
    if (!(args.fileName ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
        throw new IllegalArgumentException("Invalid file name '${args.fileName}'. Only letters, numbers, hyphens, underscores, and periods are allowed. Cannot start with a period.")
    }

    // If file already exists, back it up first
    def backedUp = false
    def backupFileName = null
    def identicalContent = false
    try {
        def existingBytes = downloadHubFile(args.fileName)
        if (existingBytes != null && new String(existingBytes, "UTF-8") == args.content.toString()) {
            identicalContent = true
            // Identical-content write (e.g. a retry after a dropped response): a
            // backup would duplicate the very bytes being written, and a fresh
            // timestamped file per retry is unbounded growth -- the exact
            // additional side effect the tool's idempotentHint promises not to
            // have. Skip the backup; the overwrite below is a byte-level no-op.
            mcpLog("info", "file-manager", "Existing '${args.fileName}' already matches the incoming content -- skipping backup")
        } else if (existingBytes != null) {
            // File exists — create a backup before overwriting
            def dotIndex = args.fileName.lastIndexOf('.')
            def baseName = dotIndex > 0 ? args.fileName.substring(0, dotIndex) : args.fileName
            def ext = dotIndex > 0 ? args.fileName.substring(dotIndex) : ""
            def ts = new Date().format("yyyyMMdd-HHmmss")
            backupFileName = "${baseName}_backup_${ts}${ext}"
            uploadHubFile(backupFileName, existingBytes)
            backedUp = true
            mcpLog("info", "file-manager", "Backed up existing '${args.fileName}' to '${backupFileName}' before overwriting (${existingBytes.length} bytes)")
        }
    } catch (Exception e) {
        // File doesn't exist or can't be read — that's fine, proceed with write
        mcpLog("debug", "file-manager", "No existing file '${args.fileName}' to back up: ${e.message}")
    }

    // Write the file
    try {
        uploadHubFile(args.fileName, args.content.getBytes("UTF-8"))
        mcpLog("info", "file-manager", "Wrote file '${args.fileName}' (${args.content.length()} chars)")

        def result = [
            success: true,
            message: backedUp
                ? "File '${args.fileName}' updated. Previous version backed up as '${backupFileName}'."
                : (identicalContent
                    ? "File '${args.fileName}' already contained this exact content (no backup needed)."
                    : "File '${args.fileName}' created."),
            fileName: args.fileName,
            contentLength: args.content.length(),
            directDownload: "http://<HUB_IP>/local/${args.fileName}"
        ]
        if (backedUp) {
            result.backupFile = backupFileName
            result.backupDownload = "http://<HUB_IP>/local/${backupFileName}"
        }
        return result
    } catch (Exception e) {
        mcpLogError("file-manager", "Failed to write file '${args.fileName}'", e)
        return [
            success: false,
            error: "Failed to write file '${args.fileName}': ${e.message}"
        ]
    }
}

def toolDeleteFile(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")

    // Skip auto-backup for files that are already backups (prevent infinite backup chains)
    def isBackupFile = args.fileName.contains("_backup_") || args.fileName.startsWith("mcp-backup-") || args.fileName.startsWith("mcp-prerestore-")

    // Back up the file before deleting (unless it's already a backup file)
    def backedUp = false
    def backupFileName = null
    if (!isBackupFile) {
        try {
            def bytes = downloadHubFile(args.fileName)
            if (bytes == null) throw new Exception("File not found")
            def dotIndex = args.fileName.lastIndexOf('.')
            def baseName = dotIndex > 0 ? args.fileName.substring(0, dotIndex) : args.fileName
            def ext = dotIndex > 0 ? args.fileName.substring(dotIndex) : ""
            def ts = new Date().format("yyyyMMdd-HHmmss")
            backupFileName = "${baseName}_backup_${ts}${ext}"
            uploadHubFile(backupFileName, bytes)
            backedUp = true
            mcpLog("info", "file-manager", "Backed up '${args.fileName}' to '${backupFileName}' before deletion (${bytes.length} bytes)")
        } catch (Exception e) {
            mcpLog("warn", "file-manager", "Could not back up '${args.fileName}' before deletion: ${e.message}")
        }
    } else {
        mcpLog("debug", "file-manager", "Skipping auto-backup for '${args.fileName}' -- file is itself a backup")
    }

    // Delete the file
    try {
        deleteHubFile(args.fileName)
        mcpLog("info", "file-manager", "Deleted file '${args.fileName}'")

        def result = [
            success: true,
            message: backedUp
                ? "File '${args.fileName}' deleted. Backup saved as '${backupFileName}'."
                : isBackupFile
                    ? "Backup file '${args.fileName}' deleted permanently (no backup-of-backup created)."
                    : "File '${args.fileName}' deleted. WARNING: Could not create backup before deletion.",
            fileName: args.fileName
        ]
        if (backedUp) {
            result.backupFile = backupFileName
            // Resolve the hub's real IP for a clickable URL; fall back to the
            // documented <HUB_IP> placeholder (the same convention the other
            // file-manager tools use) when location.hub.localIP is unavailable.
            String hubIp = null
            try { hubIp = location?.hub?.localIP?.toString() } catch (Exception ignored) { /* fall through */ }
            result.backupDownload = "http://${hubIp ?: '<HUB_IP>'}/local/${backupFileName}"
            result.undoHint = "To recover: use 'hub_read_file' on '${backupFileName}' to view contents, or 'hub_write_file' to recreate '${args.fileName}' from the backup."
        }
        if (!backedUp && !isBackupFile) {
            result.warning = "The file contents could not be backed up before deletion. The data may be permanently lost."
        }
        return result
    } catch (Exception e) {
        mcpLogError("file-manager", "Failed to delete file '${args.fileName}'", e)
        return [
            success: false,
            error: "Failed to delete '${args.fileName}': ${e.message}",
            suggestion: "Check that the file exists. Use 'hub_list_files' to see available files."
        ]
    }
}

def _getAllToolDefinitions_partFiles() {
    return [
        // File Manager Tools
        [
            name: "hub_list_files",
            description: "List files stored in the hub's File Manager (the local web-accessible file store), returning each file's name, size, last-modified date, and direct download URL. Use this to discover available files before reading one with hub_read_file, or to confirm a write/backup landed. Read-only. For large stores, opt into pagination via the cursor parameter (page size 100).",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    files: [type: "array", description: "Files in File Manager", items: [type: "object", properties: [
                        name: [type: "string", description: "File name"],
                        directDownload: [type: "string", description: "Local download URL"],
                        size: [type: "integer", description: "Size in bytes; present when known"],
                        lastModified: [type: "string", description: "Last-modified date; present when known"]
                    ]]],
                    total: [type: "integer", description: "Total files matched"],
                    storage: [type: "string", description: "Storage location note"],
                    note: [type: "string", description: "Present on HTML-fallback parse"],
                    nextCursor: [type: "string", description: "Pagination cursor; present when more results remain"]
                ],
                required: ["files", "total"]
            ]
        ],
        [
            name: "hub_read_file",
            description: "Read the text content of a single file from the hub's File Manager by exact file name. Use after hub_list_files to fetch a named file (config, backup, exported rule/app, CSV). Read-only. Large files are returned in chunks: pass offset/length, then follow nextOffset while hasMore is true (default/max chunk 60000 chars).",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact file name (e.g., 'dashboard-backup.json', 'mcp-backup-app-123.groovy')"],
                    offset: [type: "integer", description: "Character offset to start reading from (for chunked reading of large files). Default: 0"],
                    length: [type: "integer", description: "Max characters to return in this chunk. Default/max: 60000"]
                ],
                required: ["fileName"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the read succeeded"],
                    fileName: [type: "string", description: "File read"],
                    totalLength: [type: "integer", description: "Total file length in chars"],
                    offset: [type: "integer", description: "Start offset of this chunk"],
                    chunkLength: [type: "integer", description: "Chars returned in this chunk"],
                    hasMore: [type: "boolean", description: "More chunks remain"],
                    content: [type: "string", description: "Chunk content"],
                    directDownload: [type: "string", description: "Local download URL"],
                    nextOffset: [type: "integer", description: "Offset for next chunk; present when hasMore"],
                    remainingChars: [type: "integer", description: "Chars left; present when hasMore"],
                    hint: [type: "string", description: "Next-call guidance; present when hasMore"]
                ],
                required: ["success", "fileName", "totalLength", "offset", "chunkLength", "hasMore", "content"]
            ]
        ],
        [
            name: "hub_write_file",
            description: "⚠️ Write (create or overwrite) a text file in the hub's File Manager. If a file of the same name exists this OVERWRITES it wholesale; the prior version is auto-backed up first (see backupFile in the result for recovery). Requires Write master and confirm=true — confirm the write with the user before calling. Returns the file name, chars written, and download URL.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The file name to write (e.g., 'my-config.json'). Only A-Za-z0-9, hyphens, underscores, and periods allowed."],
                    content: [type: "string", description: "The text content to write to the file"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the write."]
                ],
                required: ["fileName", "content", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the write succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    fileName: [type: "string", description: "File written"],
                    contentLength: [type: "integer", description: "Chars written"],
                    directDownload: [type: "string", description: "Local download URL"],
                    backupFile: [type: "string", description: "Backup name; present when an existing file was overwritten"],
                    backupDownload: [type: "string", description: "Backup download URL; present with backupFile"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_file",
            description: "⚠️ Permanently delete a file from the hub's File Manager. The file is auto-backed up before deletion (see backupFile/undoHint in the result for recovery), but the original is removed. Tell the user and get approval first. Requires Write master and confirm=true.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact file name to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the deletion."]
                ],
                required: ["fileName", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the deletion succeeded"],
                    message: [type: "string", description: "Human-readable result, including backup status"],
                    fileName: [type: "string", description: "Name of the file that was deleted"],
                    backupFile: [type: "string", description: "Name of the auto-created backup file (present when a backup was made)"],
                    backupDownload: [type: "string", description: "URL to download the backup (present when a backup was made)"],
                    undoHint: [type: "string", description: "Guidance for recovering the deleted file (present when a backup was made)"],
                    warning: [type: "string", description: "Present when the file could not be backed up before deletion"]
                ],
                required: ["success", "message", "fileName"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partFiles() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Files (read)
        "hub_list_files", "hub_read_file"
    ]
}

def _idempotentWriteToolNames_partFiles() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Files
        "hub_write_file", "hub_delete_file"
    ]
}

def _toolDisplayMeta_partFiles() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // File Manager
        hub_list_files: [title: "List Files", summary: "List all files in the hub File Manager."],
        hub_read_file: [title: "Read File", summary: "Read a File Manager file's contents."],
        hub_write_file: [title: "Write File", summary: "Create or update a File Manager file (auto-backs up existing)."],
        hub_delete_file: [title: "Delete File", summary: "Delete a File Manager file (auto-backs up first)."]
    ]
}
