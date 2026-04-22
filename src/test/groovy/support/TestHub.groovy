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
 * As of hubitat_ci v0.28.6 (the tag pinned in build.gradle at the time of this
 * file's creation), Hub declares getZigbeeId()/getUptime() but does NOT declare
 * getZwaveVersion()/getZigbeeChannel(). Revisit on eighty20results bumps — if a
 * newer tag adds either property to the interface, drop it from the RUNTIME-ONLY
 * section and let @AutoImplement absorb the new abstract.
 *
 * Usage: {@code new TestHub(zwaveVersion: '7.17.1', uptime: 172800G)}
 */
@AutoImplement
class TestHub implements Hub {
    // --- INTERFACE (declared abstract on Hub — auto-generated getters satisfy the contract) ---
    String zigbeeId
    BigInteger uptime

    // --- RUNTIME-ONLY (not on Hub interface — property-access only, resolved
    // via Groovy's dynamic property dispatch when tools read e.g.
    // location.hub.zwaveVersion — see hubitat-mcp-server.groovy:5155 for the
    // zwaveVersion read and :5202 for the zigbeeChannel read) ---
    String zwaveVersion
    Integer zigbeeChannel
}
