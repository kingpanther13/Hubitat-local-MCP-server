package support

import groovy.transform.AutoImplement
import me.biocomp.hubitat_ci.api.common_api.Hub

/**
 * Hub stub for tests that exercise {@code location.hub.X} property reads in the
 * server. {@code @AutoImplement} fills the Hub interface with default null/zero
 * returns; this class adds the handful of properties the MCP server actually
 * reads — some declared on the Hub interface ({@code zigbeeId}, {@code uptime})
 * and some not ({@code zwaveVersion}, {@code zigbeeChannel}, which Hubitat's real
 * Hub class exposes at runtime but eighty20results' {@code Hub} interface stub
 * doesn't declare). The latter are accessible via dynamic Groovy property
 * dispatch on concrete TestHub instances even though they aren't part of the
 * interface contract, which mirrors how the real hub runtime behaves.
 *
 * Usage: {@code new TestHub(zwaveVersion: '7.17.1', uptime: 172800G)}
 */
@AutoImplement
class TestHub implements Hub {
    // Declared on Hub — auto-generated getters satisfy the abstract contract.
    String zigbeeId
    BigInteger uptime

    // Not declared on Hub — property-access only, resolved via Groovy's
    // dynamic property dispatch when tools read `hub.zwaveVersion` etc.
    String zwaveVersion
    Integer zigbeeChannel
}
