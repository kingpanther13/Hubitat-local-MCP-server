package support

import groovy.transform.AutoImplement
import me.biocomp.hubitat_ci.api.common_api.Hub

/**
 * Hub stub for tests that exercise {@code location.hub.X} property reads in the
 * server. {@code @AutoImplement} fills the Hub interface with default null/zero
 * returns; this class adds the handful of properties the MCP server actually
 * reads — some declared on the Hub interface ({@code zigbeeId}, {@code uptime})
 * and some not ({@code zwaveVersion}, {@code zigbeeChannel}), which Hubitat's
 * real Hub class exposes at runtime but eighty20results' {@code Hub} interface
 * stub doesn't declare. The latter are accessible via dynamic Groovy property
 * dispatch on concrete TestHub instances even though they aren't part of the
 * interface contract — mirroring how the real hub runtime behaves.
 *
 * As of hubitat_ci v0.28.6 (the tag pinned in build.gradle), Hub declares
 * getZigbeeId()/getUptime()/getFirmwareVersionString()/getLocalIP() but does NOT
 * declare getZwaveVersion()/getZigbeeChannel(). Revisit on eighty20results bumps — if
 * a newer tag adds either remaining property to the interface, move it up to the
 * INTERFACE section.
 *
 * Usage: {@code new TestHub(zwaveVersion: '7.17.1', uptime: 172800G)}
 */
@AutoImplement
class TestHub implements Hub {
    // --- INTERFACE (declared abstract on Hub — auto-generated getters satisfy the contract) ---
    String zigbeeId
    BigInteger uptime
    // location.hub.firmwareVersionString — read by toolInstallBundle to pick the
    // bundle endpoint (>= 2.3.8.108 -> /bundle2/uploadZipFromUrl, else /bundle/...).
    // (Declared on Hub in v0.28.6; it sat under RUNTIME-ONLY until this was checked.)
    String firmwareVersionString
    // location.hub.localIP — hub_get_info reports it, and the transport's Origin check
    // uses it as one of the server-known identities an inbound Origin may name.
    String localIP
    // location.hub.hardwareID — internal platform id ("000D" on both a C-7 and a C-8 Pro).
    // hub_get_info surfaces it as platformHardwareId; the real model comes from /hub/details/json.
    String hardwareID

    // --- RUNTIME-ONLY (not on Hub interface — property-access only, resolved
    // via Groovy's dynamic property dispatch when tools read e.g.
    // location.hub.zwaveVersion — read by toolGetHubInfo in libraries/mcp-system-lib.groovy
    // and the radio tools in libraries/mcp-diagnostics-lib.groovy) ---
    String zwaveVersion
    Integer zigbeeChannel
}
