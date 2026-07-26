package support

/**
 * Thrown by {@link HubInternalGetMock} when a spec calls an unregistered path.
 *
 * Deliberately a plain RuntimeException and NOT an IllegalStateException: production reserves ISE
 * for the ?-in-path guard in {@code _hubRequest}, and several callers RETHROW an ISE rather than
 * degrading (see {@code _radioGetSafe} and the McpDevicesLib fallback legs). An unstubbed path is
 * standing in for a hub fault, so it must ride those degradation paths exactly like a real HTTP
 * error would -- if it were an ISE, every spec that produces its {@code {error}} branch by leaving a
 * path unregistered would get the guard's rethrow instead of the behaviour it is testing.
 *
 * The message is unchanged from the original throw, so the many specs asserting
 * {@code message.contains("Unstubbed hubInternalGet")} keep working.
 */
class UnstubbedPathException extends RuntimeException {
    UnstubbedPathException(String message) {
        super(message)
    }
}
