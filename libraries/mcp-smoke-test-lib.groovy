library(name: "McpSmokeTestLib", namespace: "mcp", author: "kingpanther13", description: "Throwaway canary for the issue #209 bundle-delivery + #include smoke test; the app #includes it and folds its marker into hub_get_info to prove the path resolves on a real hub. Temporary -- do NOT delete while it is still #included (the app would fail to compile); removed once the modularization is validated.")

String mcpSmokeTestMarker() {
    // Byte-level canary touch (unified-delivery PR): forces the e2e deploy off its
    // skip-when-identical-to-main path so the run exercises the full artifact install.
    "smoke-ok-v1"
}
