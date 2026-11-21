library(name: "McpFilesLib", namespace: "mcp", author: "kingpanther13", description: "File Manager tool implementations (hub_list_files/hub_read_file/hub_write_file/hub_delete_file) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// Hubitat's sandbox does not expose java.util.Locale. File Manager names are
// ASCII-only, so use a deterministic ASCII fold instead of the process locale.
private String _filesFoldCase(value) {
    return value?.toString()?.tr('ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
}

private String _filesDownloadUrl(String name) {
    String hubIp = null
    try { hubIp = location?.hub?.localIP?.toString() } catch (Exception ignored) { /* placeholder below */ }
    return "http://${hubIp ?: '<HUB_IP>'}/local/${name}".toString()
}

@groovy.transform.Field static final String FILES_GUIDE_POINTER = "See hub_get_tool_guide(section='file_manager')."
// A failed listing proves neither presence nor absence.
@groovy.transform.Field static final String FILES_KIND_UNKNOWN = "unknown"

// The read, write and delete tools act on root files only, and the hub accepts only these
// characters in a name it uploads; a leading period fails its upload.
private void _filesRequireRootName(String fileName, String toolName) {
    if (fileName.contains("/")) {
        String folder = fileName.substring(0, fileName.lastIndexOf('/'))
        String rootOnly = "'${fileName}' names a file in a subfolder; ${toolName} acts on root files only."
        // A folder the hub cannot list gets no listing hint, because that call would fail.
        throw new IllegalArgumentException(toolName == "hub_write_file"
            ? "'${fileName}' names a file in a subfolder. hub_write_file writes root files only; it cannot write into a subfolder.".toString()
            : _filesValidFolder(folder)
                ? "${rootOnly} List the folder with hub_list_files(folder: '${folder}'); its files download from that listing's downloadUrlBase.".toString()
                : rootOnly)
    }
    if (!(fileName ==~ /^[A-Za-z0-9_-][A-Za-z0-9._-]*$/)) {
        throw new IllegalArgumentException("Invalid file name '${fileName}'. Only letters, numbers, hyphens, underscores and periods; cannot start with a period.")
    }
}

// The hub cannot create or list a folder whose name has a space or a dot.
private boolean _filesValidFolder(String folder) {
    return folder ==~ /[A-Za-z0-9_\/-]+/ && !folder.split("/").any { it == "" }
}

// A download of a name the hub does not hold throws NoSuchFileException, which settles it
// without a listing.
private boolean _filesDownloadMissing(Exception e) {
    return e.class.simpleName == "NoSuchFileException"
}

// A folder and an unreadable file both fail their download with FileSystemException, so only the
// listing tells them apart.
private String _filesRootEntryKind(String name) {
    try {
        def entry = _fileManagerRootEntries().find { it.name == name }
        return entry == null ? null : (entry.type == "dir" ? "dir" : "file")
    } catch (Exception e) {
        mcpLog("warn", "file-manager", "Could not list File Manager to check '${name}': ${e.message}")
        return FILES_KIND_UNKNOWN
    }
}

private Map _filesFolderResult(String name) {
    return [success: false, fileName: name, error: "'${name}' is a folder, not a file.",
            note: "List it with hub_list_files(folder: '${name}'). ${FILES_GUIDE_POINTER}".toString()]
}

private Map _filesMissing(String fileName, String error, String extraNote = null) {
    return [success: false, fileName: fileName, error: error,
            note: "Use hub_list_files (filter narrows it) to find the exact file name.${extraNote ? ' ' + extraNote : ''} ${FILES_GUIDE_POINTER}".toString()]
}

private int _filesIntArg(Map args, String key, int defaultValue, int min, int max) {
    def v = args?.get(key)
    if (v == null) return defaultValue
    String t = (v instanceof Number || v instanceof CharSequence) ? v.toString().trim() : ""
    String outOfRange = "${key} must be an integer from ${min} to ${max}; got ${v}."
    // JSON clients may send 3 as 3.0; a number with no fractional part is still that integer.
    if (v instanceof Number && !t.isLong() && t.isBigDecimal()) {
        BigDecimal d = new BigDecimal(t)
        // Range first: toBigInteger() on an exponent such as 1E+1000000000 builds the whole number.
        if (d.compareTo(new BigDecimal(min)) < 0 || d.compareTo(new BigDecimal(max)) > 0) {
            throw new IllegalArgumentException(outOfRange)
        }
        if (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0) t = d.toBigInteger().toString()
    }
    if (!t.isLong() || t.toLong() < min || t.toLong() > max) {
        throw new IllegalArgumentException(outOfRange)
    }
    return t.toInteger()
}

private List _filesBackupNameParts(String fileName) {
    int dotIndex = fileName.lastIndexOf('.')
    return dotIndex > 0 ? [fileName.substring(0, dotIndex), fileName.substring(dotIndex)] : [fileName, ""]
}

private String _filesWriteBackupCopy(String fileName, byte[] bytes) {
    // <base>_backup_<yyyyMMdd-HHmmss-SSS><ext>: the copy hub_write_file and hub_delete_file take
    // first. Milliseconds keep two writes in one second from sharing a name; older copies carry
    // the second-only form, which pruning still recognises.
    List parts = _filesBackupNameParts(fileName)
    String backupFileName = "${parts[0]}_backup_${new Date(now()).format("yyyyMMdd-HHmmss-SSS")}${parts[1]}"
    uploadHubFile(backupFileName, bytes)
    return backupFileName
}

private String _filesCopyRetentionNote(String fileName) {
    return "The newest ${FILE_BACKUP_COPIES} backup copies of '${fileName}' are kept; older copies are deleted after its next successful write or delete.".toString()
}

private Map _filesPruneBackupCopies(String fileName, String keepName = null) {
    // Keep the newest FILE_BACKUP_COPIES copies of exactly this file name. Best-effort: a
    // failed listing or delete leaves copies in place, is reported as warning, and never fails
    // the write or delete it follows. keepName is the copy this operation just took: it is
    // never a candidate, even if a clock step or a repeated DST hour makes its name sort oldest.
    List parts = _filesBackupNameParts(fileName)
    def copyPattern = ~("^" + java.util.regex.Pattern.quote(parts[0]) + "_backup_(\\d{8}-\\d{6})(-\\d{3})?" + java.util.regex.Pattern.quote(parts[1]) + "\$")
    List names
    try { names = _fileManagerRootFiles().collect { it.name } }
    catch (Exception e) {
        mcpLog("warn", "file-manager", "Could not list File Manager to prune old backup copies of '${fileName}': ${e.message}")
        return [removed: [], warning: "Old backup copies of '${fileName}' were not pruned: the File Manager listing failed (${e.message}). More than ${FILE_BACKUP_COPIES} copies may remain.".toString()]
    }
    // Sort on the timestamp itself (a second-only name counts as millisecond 000).
    Closure age = { String name ->
        def m = name =~ copyPattern
        m.matches() ? (m.group(1) + (m.group(2) ?: "-000")) : ""
    }
    boolean keepingNew = keepName != null && names.contains(keepName)
    List copies = names.findAll { it ==~ copyPattern && it != keepName }
    List stale = copies.sort { a, b -> age(b) <=> age(a) }.drop(FILE_BACKUP_COPIES - (keepingNew ? 1 : 0))
    List removed = []
    List failed = []
    stale.each { name ->
        try {
            deleteHubFile(name)
            removed << name
        } catch (Exception e) {
            failed << name
            mcpLog("warn", "file-manager", "Could not delete old backup copy '${name}': ${e.message}")
        }
    }
    if (removed) mcpLog("info", "file-manager", "Removed ${removed.size()} old backup copies of '${fileName}' (keeping the newest ${FILE_BACKUP_COPIES})")
    String warning = failed ? "Could not delete old backup copies ${failed} of '${fileName}'; more than ${FILE_BACKUP_COPIES} copies remain.".toString() : null
    return [removed: removed, warning: warning]
}

private void _filesReportCopyRetention(Map result, String fileName, Map prune) {
    // Copy-retention fields for a write or delete result that took a backup copy.
    result.backupRetention = "copies"
    result.backupRetentionNote = _filesCopyRetentionNote(fileName)
    if (prune?.removed) result.removedOldBackups = prune.removed
    if (prune?.warning) result.pruneWarning = prune.warning
}

def toolListFiles(args = null) {
    if (args?.filter != null && !(args.filter instanceof CharSequence)) {
        throw new IllegalArgumentException("filter must be a string (a case-insensitive substring of the file name), not ${args.filter instanceof Number ? 'a number' : args.filter instanceof Map ? 'an object' : args.filter instanceof List ? 'an array' : 'a non-string value'}")
    }
    def cursor = args?.cursor
    def filterLower = _filesFoldCase(args?.filter?.toString()?.trim())
    def folder = args?.folder?.toString()?.trim()?.replaceAll(/^\/+|\/+$/, "")
    if (folder && !_filesValidFolder(folder)) {
        throw new IllegalArgumentException("folder must be a relative File Manager folder path such as 'webcore' (letters, digits, underscore and hyphen, '/' between parts; no spaces or dots).")
    }
    Map listing
    try { listing = _fileManagerListing(folder ?: null) }
    catch (Exception e) {
        mcpLogError("file-manager", "File Manager listing failed${folder ? " for folder '${folder}'" : ''}", e)
        return [success: false, error: "Could not list File Manager: ${e.message}",
                note: "Nothing was changed. Retry in a moment; if it keeps failing, check Settings > File Manager in the hub web UI. ${FILES_GUIDE_POINTER}".toString()]
    }
    List fileList = listing.files.collect { f ->
        Map entry = [name: f.name]
        if (f.type) entry.type = f.type
        if (f.size != null && f.type != "dir") entry.size = f.size
        if (f.date != null) entry.lastModified = f.date
        if (f.backupIncluded != null) entry.backupIncluded = f.backupIncluded
        return entry
    }.sort { a, b -> a.name <=> b.name }
    if (filterLower) fileList = fileList.findAll { _filesFoldCase(it.name).contains(filterLower) }
    mcpLog("info", "file-manager", "Listed ${fileList.size()}${filterLower ? ' matching' : ''} files in File Manager")
    def paged = _paginateList(fileList, cursor, 100, "hub_list_files")
    def res = [files: paged.page, total: fileList.size(), downloadUrlBase: _filesDownloadUrl(folder ? "${folder}/" : "")]
    if (folder) res.folder = folder
    // The hub answers an empty subfolder and a missing one identically.
    if (folder && fileList.isEmpty() && !filterLower) res.note = "Folder '${folder}' is empty or does not exist; the root listing shows the folders as type:'dir'."
    if (listing.freeSpace != null) res.freeSpaceBytes = listing.freeSpace
    if (listing.excludedFiles != null) res.filesExcludedFromFullBackup = listing.excludedFiles
    if (cursor != null && paged.nextCursor != null) res.nextCursor = paged.nextCursor
    return res
}

def toolReadFile(args) {
    if (!args.fileName) throw new IllegalArgumentException("fileName is required.")
    String fileName = args.fileName.toString()
    _filesRequireRootName(fileName, "hub_read_file")
    int requestedOffset = _filesIntArg(args, "offset", 0, 0, Integer.MAX_VALUE)
    int requestedLength = _filesIntArg(args, "length", defaultReadChunkChars() as int, 1, 60000)

    mcpLog("debug", "file-manager", "Reading file: ${fileName} (offset: ${requestedOffset}, length: ${requestedLength})")
    def content
    try {
        def bytes = downloadHubFile(fileName)
        if (bytes == null) throw new Exception("the hub returned no content")
        content = new String(bytes, "UTF-8")
    } catch (Exception e) {
        String kind = _filesDownloadMissing(e) ? null : _filesRootEntryKind(fileName)
        if (kind == null) {
            mcpLog("info", "file-manager", "Read of '${fileName}' refused: no such file")
            return _filesMissing(fileName, "File '${fileName}' does not exist in File Manager.")
        }
        if (kind == "dir") return _filesFolderResult(fileName)
        mcpLogError("file-manager", "Failed to read file '${fileName}'", e)
        return [
            success: false,
            fileName: fileName,
            error: "File '${fileName}' could not be read: ${e.message}",
            note: "Check the exact name with hub_list_files, or download it from directDownload. ${FILES_GUIDE_POINTER}".toString(),
            directDownload: _filesDownloadUrl(fileName)
        ]
    }

    def totalLength = content.length()
    long requestedEnd = (long) requestedOffset + requestedLength
    int endIndex = (int) Math.min(requestedEnd, (long) totalLength)
    def chunk = (requestedOffset < totalLength) ? content.substring(requestedOffset, endIndex) : ""
    def hasMore = endIndex < totalLength

    def result = [
        success: true,
        fileName: fileName,
        totalLength: totalLength,
        offset: requestedOffset,
        chunkLength: chunk.length(),
        hasMore: hasMore,
        content: chunk,
        directDownload: _filesDownloadUrl(fileName)
    ]
    if (hasMore) {
        result.nextOffset = endIndex
        result.remainingChars = totalLength - endIndex
        result.hint = "Call again with offset: ${endIndex} to get the next chunk."
    }

    mcpLog("info", "file-manager", "Read file '${fileName}' (${totalLength} chars total, returned offset ${requestedOffset}..${endIndex}${hasMore ? ', more available' : ''})")
    return result
}

def toolWriteFile(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required.")
    if (args.content == null) throw new IllegalArgumentException("content is required.")
    String fileName = args.fileName.toString()
    _filesRequireRootName(fileName, "hub_write_file")
    String content = args.content.toString()

    byte[] existingBytes = null
    String downloadError = "the hub returned no content"
    boolean missing = false
    try { existingBytes = downloadHubFile(fileName) }
    catch (Exception e) { downloadError = e.message ?: e.class.simpleName; missing = _filesDownloadMissing(e) }
    if (existingBytes == null) {
        // Any other failed download may hide an existing file, and overwriting one whose content
        // could not be copied first would lose it, so then only a name the listing lacks is created.
        String kind = missing ? null : _filesRootEntryKind(fileName)
        if (kind == "dir") return _filesFolderResult(fileName)
        if (kind != null) {
            mcpLog("warn", "file-manager", "Write of '${fileName}' refused: its current content could not be read (${downloadError})")
            String cause = kind == FILES_KIND_UNKNOWN
                ? "The File Manager listing also failed, so whether '${fileName}' exists is unknown."
                : "'${fileName}' exists but its content could not be downloaded."
            // The hub's message stays in error.
            return [
                success: false,
                fileName: fileName,
                error: "Could not read the current '${fileName}' to take its backup copy (${downloadError}); nothing was written.".toString(),
                note: "${cause} Retry once the read recovers, write the content under a new name, or save the current content from directDownload, then upload the new content over the file in Settings > File Manager. ${FILES_GUIDE_POINTER}".toString(),
                directDownload: _filesDownloadUrl(fileName)
            ]
        }
        mcpLog("debug", "file-manager", "No existing file '${fileName}' to back up")
    }
    // Compare the bytes that will be written, not decoded text: invalid UTF-8 in the existing
    // file decodes to replacement characters that incoming text can match while the bytes differ.
    byte[] incomingBytes = content.getBytes("UTF-8")
    if (existingBytes != null && existingBytes.length == incomingBytes.length && (existingBytes as List) == (incomingBytes as List)) {
        // Same bytes again: a rewrite or another backup copy would only add side effects, and
        // the idempotent hint promises none.
        mcpLog("info", "file-manager", "Existing '${fileName}' already matches the incoming content -- nothing written")
        return [
            success: true,
            unchanged: true,
            message: "File '${fileName}' already holds this exact content; nothing was written.",
            fileName: fileName,
            contentLength: content.length(),
            directDownload: _filesDownloadUrl(fileName)
        ]
    }
    String backupFileName = null
    if (existingBytes != null) {
        // File exists: an overwrite without its backup copy would lose the old content.
        try {
            backupFileName = _filesWriteBackupCopy(fileName, existingBytes)
        } catch (Exception e) {
            mcpLogError("file-manager", "Could not back up existing '${fileName}' before overwriting", e)
            return [
                success: false,
                fileName: fileName,
                error: "Could not back up existing '${fileName}' before overwriting: ${e.message}".toString(),
                note: "Nothing was written; '${fileName}' still holds its previous content. Retry once File Manager accepts writes. ${FILES_GUIDE_POINTER}".toString()
            ]
        }
        mcpLog("info", "file-manager", "Backed up existing '${fileName}' to '${backupFileName}' before overwriting (${existingBytes.length} bytes)")
    }

    try {
        uploadHubFile(fileName, incomingBytes)
        mcpLog("info", "file-manager", "Wrote file '${fileName}' (${content.length()} chars)")

        def result = [
            success: true,
            message: backupFileName
                ? "File '${fileName}' updated. Previous version backed up as '${backupFileName}'."
                : "File '${fileName}' created.",
            fileName: fileName,
            contentLength: content.length(),
            directDownload: _filesDownloadUrl(fileName)
        ]
        if (backupFileName) {
            result.backupFile = backupFileName
            result.backupDownload = _filesDownloadUrl(backupFileName)
            // Pruned only after the write landed, so a failed write keeps every older copy.
            _filesReportCopyRetention(result, fileName, _filesPruneBackupCopies(fileName, backupFileName))
        }
        return result
    } catch (Exception e) {
        mcpLogError("file-manager", "Failed to write file '${fileName}'", e)
        return [
            success: false,
            fileName: fileName,
            error: "Failed to write file '${fileName}': ${e.message}",
            note: backupFileName
                ? "Its previous content is kept in the backup copy '${backupFileName}'. Read '${fileName}' to see what it holds now before retrying. ${FILES_GUIDE_POINTER}".toString()
                : "The hub reported the upload failed; check with hub_list_files whether '${fileName}' now exists before retrying. ${FILES_GUIDE_POINTER}".toString()
        ]
    }
}

def toolDeleteFile(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required.")
    String fileName = args.fileName.toString()
    _filesRequireRootName(fileName, "hub_delete_file")
    String missingError = "File '${fileName}' does not exist in File Manager; nothing was deleted."

    // Skip auto-backup for files that are already backups (prevent infinite backup chains)
    def isBackupFile = fileName.contains("_backup_") || fileName.startsWith("mcp-backup-") ||
        fileName.startsWith("mcp-rm-backup-") || fileName.startsWith("mcp-prerestore-")

    // deleteHubFile does not fail for a missing name, so existence is checked first: through the
    // backup download for a normal file, and through the listing when that download fails for
    // another reason or when no download is taken. A failed listing proves nothing, so the delete
    // goes ahead.
    String backupFileName = null
    boolean existenceUnknown = false
    if (!isBackupFile) {
        byte[] bytes = null
        boolean missing = false
        String downloadError = null
        try { bytes = downloadHubFile(fileName) }
        catch (Exception e) {
            downloadError = e.message ?: e.class.simpleName
            missing = _filesDownloadMissing(e)
            mcpLog("debug", "file-manager", "Could not download '${fileName}' before deletion: ${downloadError}")
        }
        if (bytes == null && _loadLimiterRefusedMessage(downloadError)) {
            // The no-copy delete below is for a file that can never be read; a limiter refusal
            // passes, so deleting now would lose a copy that a retry could take. The hub's message
            // stays in error.
            mcpLog("warn", "file-manager", "Delete of '${fileName}' refused: the load limiter refused its backup download (${downloadError})")
            return [
                success: false,
                fileName: fileName,
                error: "Could not read '${fileName}' to take its backup copy (${downloadError}); nothing was deleted.".toString(),
                note: "The hub's load limiter refused the download, which is temporary. Nothing was deleted; retry in a moment. ${FILES_GUIDE_POINTER}".toString()
            ]
        }
        if (bytes == null) {
            String kind = missing ? null : _filesRootEntryKind(fileName)
            if (kind == "dir") return _filesFolderResult(fileName)
            if (kind == null) {
                // The name may be gone because an earlier delete of it landed; its copy shows where.
                String base = _filesBackupNameParts(fileName)[0]
                return _filesMissing(fileName, missingError,
                    "An earlier call may already have deleted it; its backup copy is listed by hub_list_files(filter: '${base}_backup_').")
            }
            existenceUnknown = kind == FILES_KIND_UNKNOWN
            mcpLog("warn", "file-manager", existenceUnknown
                ? "Could not check whether '${fileName}' exists before deletion: its download and the listing both failed"
                : "Could not back up '${fileName}' before deletion: its content could not be downloaded")
        } else {
            try {
                backupFileName = _filesWriteBackupCopy(fileName, bytes)
                mcpLog("info", "file-manager", "Backed up '${fileName}' to '${backupFileName}' before deletion (${bytes.length} bytes)")
            } catch (Exception e) {
                mcpLog("warn", "file-manager", "Could not back up '${fileName}' before deletion: ${e.message}")
            }
        }
    } else {
        String kind = _filesRootEntryKind(fileName)
        if (kind == "dir") return _filesFolderResult(fileName)
        if (kind == null) return _filesMissing(fileName, missingError)
        existenceUnknown = kind == FILES_KIND_UNKNOWN
        mcpLog("debug", "file-manager", "Skipping auto-backup for '${fileName}' -- file is itself a backup")
    }

    try {
        Map deletionDetails = _deleteHubFileAndUnlinkBackups(fileName)
        mcpLog("info", "file-manager", "Deleted file '${fileName}'")

        def result = [
            success: true,
            message: backupFileName
                ? "File '${fileName}' deleted. Backup saved as '${backupFileName}'."
                : isBackupFile
                    ? "Backup file '${fileName}' deleted permanently (no backup-of-backup created)."
                    : "File '${fileName}' deleted. WARNING: Could not create backup before deletion.",
            fileName: fileName
        ]
        if (backupFileName) {
            result.backupFile = backupFileName
            result.backupDownload = _filesDownloadUrl(backupFileName)
            result.undoHint = "To recover a text file, read '${backupFileName}' with hub_read_file (every chunk, following nextOffset) and recreate '${fileName}' with hub_write_file. For a binary file, download backupDownload and upload it in Settings > File Manager."
            // Pruned only after the delete landed, so a failed delete keeps every older copy.
            _filesReportCopyRetention(result, fileName, _filesPruneBackupCopies(fileName, backupFileName))
        }
        if (existenceUnknown) {
            // The listing could not say whether the name exists, so nothing proves a file went away.
            String failed = isBackupFile ? "the File Manager listing failed" : "its download and the File Manager listing both failed"
            result.message = "Could not check whether '${fileName}' exists: ${failed}. The hub was asked to delete it; it may not have existed.".toString()
            result.warning = "${isBackupFile ? '' : 'No backup copy was taken. '}Check with hub_list_files whether '${fileName}' is still listed.".toString()
        } else if (!backupFileName && !isBackupFile) {
            result.warning = "The file contents could not be backed up before deletion. The data may be permanently lost."
        }
        result.putAll(deletionDetails)
        if (deletionDetails.partial) {
            result.message = "${result.message} Backup bookkeeping did not complete; see note."
            String cleanupWarning = "The file is gone but its backup manifest entry could not be cleaned up. Do not retry the deletion."
            result.warning = result.warning ? "${result.warning} ${cleanupWarning}".toString() : cleanupWarning
        }
        return result
    } catch (Exception e) {
        mcpLogError("file-manager", "Failed to delete file '${fileName}'", e)
        // The index load runs before the hub delete, so a refusal here means nothing was deleted.
        if (!backupFileName) {
            Map unavailable = _itemBackupIndexUnavailableResult(e)
            if (unavailable != null) return unavailable + [fileName: fileName, note: "${unavailable.note} ${FILES_GUIDE_POINTER}".toString()]
        }
        return [
            success: false,
            fileName: fileName,
            error: "Failed to delete '${fileName}': ${e.message}",
            note: backupFileName
                ? "Its backup copy '${backupFileName}' was taken first. Check with hub_list_files whether '${fileName}' is still listed before retrying. ${FILES_GUIDE_POINTER}".toString()
                : "Check with hub_list_files whether '${fileName}' is still listed before retrying. ${FILES_GUIDE_POINTER}".toString()
        ]
    }
}

def _getAllToolDefinitions_partFiles() {
    return [
        // File Manager Tools
        [
            name: "hub_list_files",
            description: "List files in the hub's File Manager (its local web file store): name, size in bytes and last-modified epoch ms per file[[FLAT_TRIM]], subfolders as type 'dir'[[/FLAT_TRIM]]; a file downloads from downloadUrlBase + name. Optionally filter by a case-insensitive name substring[[FLAT_TRIM]], or list a subfolder[[/FLAT_TRIM]]. Use it to find a file before hub_read_file, or to confirm a write or backup landed. Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    filter: [type: "string", description: "Optional case-insensitive substring to match against file names, e.g. \"backup\" or \"mcp-rm-backup\"."],
                    folder: [type: "string", description: "[[FLAT_TRIM]]Optional subfolder, e.g. \"webcore\".[[/FLAT_TRIM]]"],
                    cursor: [type: "string", description: "Opt-in pagination cursor.[[FLAT_TRIM]] Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100).[[/FLAT_TRIM]]"]
                ]
            ]
        ],
        [
            name: "hub_read_file",
            description: "Read the text content of a single file from the hub's File Manager by exact file name.[[FLAT_TRIM]] Use after hub_list_files to fetch a named file (config, backup, exported rule/app, CSV). Pre-write rule and app snapshots do not store password-typed settings.[[/FLAT_TRIM]] Read-only.[[FLAT_TRIM]] Large files are returned in chunks: pass offset/length, then follow nextOffset while hasMore is true (default chunk 30000 chars, max 60000).[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact root file name (e.g., 'dashboard-backup.json', 'mcp-backup-app-123.groovy'); a folder or a name with '/' is refused."],
                    offset: [type: "integer", description: "Character offset to start from, 0 or more. Default 0."],
                    length: [type: "integer", description: "Max chars, 1-60000 (default 30000)."]
                ],
                required: ["fileName"]
            ]
        ],
        [
            name: "hub_write_file",
            description: "⚠️ Create or overwrite a text file in the hub's File Manager. An existing file is OVERWRITTEN wholesale after a backup copy is taken (backupFile in the result; the newest ${FILE_BACKUP_COPIES} copies per file are kept); identical content writes nothing; if the current content cannot be read, nothing is written. Requires confirm=true and a hub backup from the last 24 hours — confirm with the user first.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The root file name to write (e.g., 'my-config.json'): letters, numbers, hyphens, underscores and periods only, not starting with a period; a name with '/' is refused."],
                    content: [type: "string", description: "The text content to write."],
                    confirm: [type: "boolean", description: "Must be true: the user approved the write."]
                ],
                required: ["fileName", "content", "confirm"]
            ]
        ],
        [
            name: "hub_delete_file",
            description: "⚠️ Permanently delete a file from the hub's File Manager. A backup copy is taken first when possible (backupFile/undoHint in the result); a file that is itself a backup gets none, and if no copy can be taken (unreadable content or a failed upload) the delete still runs with a warning[[FLAT_TRIM]], except that a download the load limiter refused deletes nothing (retry)[[/FLAT_TRIM]]. Requires confirm=true and a hub backup from the last 24 hours — get user approval first.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact root file name to delete; a folder or a name with '/' is refused."],
                    confirm: [type: "boolean", description: "Must be true: the user approved the deletion."]
                ],
                required: ["fileName", "confirm"]
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
        hub_list_files: [title: "List Files", summary: "List files in the hub File Manager, optionally filtered by a name substring."],
        hub_read_file: [title: "Read File", summary: "Read a File Manager file's contents."],
        hub_write_file: [title: "Write File", summary: "Create or update a File Manager file (backs up the existing one; identical content writes nothing)."],
        hub_delete_file: [title: "Delete File", summary: "Delete a File Manager file (takes a backup copy first when possible)."]
    ]
}
