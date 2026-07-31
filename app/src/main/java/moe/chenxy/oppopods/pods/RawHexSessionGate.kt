package moe.chenxy.oppopods.pods

/** Session-scoped gate for the dangerous arbitrary-byte send path. */
class RawHexSessionGate(private val debugBuild: Boolean) {
    private var activeSessionToken: String? = null

    @Synchronized
    fun unlock(sessionToken: String): Boolean {
        activeSessionToken = sessionToken.takeIf { debugBuild && it.isNotBlank() }
        return activeSessionToken != null
    }

    @Synchronized
    fun lock(sessionToken: String? = null) {
        if (sessionToken == null || sessionToken == activeSessionToken) {
            activeSessionToken = null
        }
    }

    @Synchronized
    fun canSend(sessionToken: String?): Boolean =
        debugBuild && sessionToken != null && sessionToken == activeSessionToken
}
