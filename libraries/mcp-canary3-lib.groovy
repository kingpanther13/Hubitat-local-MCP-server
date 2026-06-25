library(name: "McpCanary3Lib", namespace: "mcp", author: "kingpanther13", description: "Third canary, structurally identical to McpSmokeTestLib but #included LAST (after McpNativeRulesLib) to test whether the LAST include position fails to bind. Diagnostic only -- remove after the bind test.")

String mcpCanary3Marker() {
    // Marker-only canary at the LAST #include slot, to isolate include-position as the cause.
    "canary3-ok-v1"
}
