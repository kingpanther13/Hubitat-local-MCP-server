I need you to thoroughly test the MCP Rule Server v0.4.4 new features and bug fixes: the backup system, file manager tools, and app/driver management. Run through each section below in order, reporting results at each step. Start by setting the log level to debug.

## Setup
1. Call `set_log_level` with level "debug"
2. Call `get_hub_health` to confirm the hub is responsive
3. Call `list_hub_apps` — we'll use **Bond Integration (app #467)** for testing
4. Call `list_hub_drivers` — we'll use the **Tessie Vehicle** driver for testing

## Part 1: File Manager Tools
5. Call `list_files` — report what files are currently in File Manager
6. Call `write_file` with fileName "mcp-test-file.txt", content "Hello from MCP test! This is a test file created by the MCP Rule Server.", confirm=true
7. Call `list_files` again — verify the new file appears
8. Call `read_file` with fileName "mcp-test-file.txt" — verify the content matches what we wrote
9. Call `write_file` again with fileName "mcp-test-file.txt", content "Updated content — this should have triggered an automatic backup of the original.", confirm=true — verify it reports a backup was created
10. Call `list_files` — verify both the file and its backup exist
11. Call `read_file` on the backup file (the one with _backup_ in the name) — verify it contains the original "Hello from MCP test!" content
12. Call `delete_file` with fileName "mcp-test-file.txt", confirm=true — verify it reports a backup was created before deletion
13. Call `list_files` — verify the original file is gone but backup files remain

## Part 2: App Backup System (using Bond Integration #467)
14. Call `get_app_source` on app ID 467 — note the source length and first few lines
15. Call `update_app_code` on app 467 with the EXACT same source code (no changes) and confirm=true — this triggers an automatic backup
16. Call `list_item_backups` — verify a backup for app_467 appears with correct metadata, fileName, and directDownload URL
17. Call `get_item_backup` with backupKey "app_467" — verify it returns the full source and the source length matches step 14

## Part 3: App Backup with Modification and Restore
18. Take the source from step 14 and add "// MCP TEST COMMENT - WILL BE RESTORED" as the very first line. Call `update_app_code` on app 467 with this modified source and confirm=true
19. Call `get_app_source` on app 467 — verify the comment is now present at the top
20. To test restore, we need the 1-hour backup window to not block us. Call `list_item_backups` and note the backup timestamp for app_467. If it still shows the original (pre-comment) version, we can restore from it. If it shows the commented version, we'll need to manually work around this — tell me what you see.
21. Call `restore_item_backup` with backupKey "app_467" and confirm=true — this should restore the original source
22. Call `get_app_source` on app 467 — verify the test comment is GONE
23. Call `list_item_backups` — verify a "prerestore_app_467" backup now exists containing the commented version

## Part 4: Create and Test a New App
24. Create a simple test app using `install_app` with this source code and confirm=true:

definition(name: "MCP Test App", namespace: "mcp-test", author: "MCP", description: "Temporary test app", category: "Testing", iconUrl: "", iconX2Url: "")
preferences { page(name: "mainPage", title: "MCP Test App", install: true, uninstall: true) { section { paragraph "This is a test app created by MCP for testing purposes." } } }
def installed() { log.info "MCP Test App installed" }
def updated() { log.info "MCP Test App updated" }

Note the new app ID returned.
25. Call `get_app_source` on the new app — verify it matches what we installed
26. Call `update_app_code` on the new app, adding "// Updated by MCP" at the top, confirm=true
27. Call `list_item_backups` — verify a backup of the original test app exists
28. Call `delete_app` on the test app with confirm=true — verify it reports success and mentions the backup file
29. Call `list_item_backups` — verify the backup for the deleted test app still exists

## Part 5: Driver Testing with Tessie Vehicle
30. Call `list_hub_drivers` and find the Tessie Vehicle driver — note its ID
31. Call `get_driver_source` on the Tessie Vehicle driver — note source length
32. Call `update_driver_code` with the EXACT same source (no changes) and confirm=true
33. Call `list_item_backups` — verify a driver backup appeared
34. Call `get_item_backup` on the driver backup — verify source length matches

## Part 6: Create and Test a New Driver
35. Create a simple test driver using `install_driver` with this source code and confirm=true:

metadata { definition(name: "MCP Test Driver", namespace: "mcp-test", author: "MCP") { capability "Sensor"; attribute "testAttr", "string" } }
def installed() { log.info "MCP Test Driver installed" }
def updated() { log.info "MCP Test Driver updated" }

Note the new driver ID returned.
36. Call `get_driver_source` on the new driver — verify it matches
37. Call `update_driver_code` on the new driver, adding "// Updated by MCP" at the top, confirm=true
38. Call `list_item_backups` — verify a backup of the original test driver exists
39. Call `delete_driver` on the test driver with confirm=true
40. Call `list_item_backups` — verify the test driver backup persists after deletion

## Part 7: Verify Debug Logs
41. Call `get_debug_logs` with limit 50 — look for and report these log entries:
    - "file-manager" component entries (list, read, write, delete, backup operations)
    - "hub-admin" component entries (backup creation, restore, pre-restore backup)
    - Any errors or warnings

## Part 8: File Manager Cleanup
42. Call `list_files` — report ALL files currently in File Manager (including any backup files created during testing)
43. Clean up test files: delete any files that start with "mcp-test-file" (the test file and its backups) using `delete_file`. Do NOT delete any mcp-backup-* files — those are app/driver source backups.
44. Call `list_files` one final time to show the clean state

## Summary
45. Report a summary: which tests passed, which failed, any unexpected behavior, and the final state of File Manager and item backups.

IMPORTANT NOTES:
- Use Bond Integration (app #467) for app testing — it's not used for anything
- Use the Tessie Vehicle driver for driver testing — it's not used for anything
- Do NOT test on MCP Rule Server or MCP Rule — that would break the testing tool itself
- When creating test apps/drivers, note the IDs so you can clean them up
- If any test fails, continue with the remaining tests and report all failures at the end
