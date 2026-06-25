library(name: "McpSmokeTest2Lib", namespace: "mcp", author: "kingpanther13", description: "Second throwaway canary, structurally identical to McpSmokeTestLib, to test whether a brand-NEW library binds via #include on a real hub. Diagnostic only -- do NOT delete while it is still #included; remove after the bind test.")

String mcpSmokeTest2Marker() {
    // Byte-level canary touch: a second new library to learn whether ADDING a new library
    // (not just changing an existing one) binds on the live hub.
    "smoke2-ok-v1"
}
