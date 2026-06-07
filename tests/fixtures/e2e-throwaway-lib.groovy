library(name: "E2eThrowawayLib", namespace: "mcptest", author: "kingpanther13", description: "Throwaway library used only by the bundle-tools e2e (install/list/delete a disposable bundle). Not included by the app; the mcptest namespace ensures it can never collide with or affect the real mcp libraries.")

String e2eThrowawayMarker() { "e2e-throwaway-v1" }
