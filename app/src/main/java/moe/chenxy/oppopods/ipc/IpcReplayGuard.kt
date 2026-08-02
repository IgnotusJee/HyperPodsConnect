package moe.chenxy.oppopods.ipc

internal class IpcReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = 30_000L,
    private val maxFutureSkewMillis: Long = 5_000L,
    private val capacity: Int = 256,
) {
    private val acceptedRequests = LinkedHashMap<String, Long>()

    @Synchronized
    fun accept(requestId: String, timestamp: Long): Boolean {
        val now = clock()
        if (timestamp < now - maxAgeMillis || timestamp > now + maxFutureSkewMillis) return false
        if (acceptedRequests.containsKey(requestId)) return false
        acceptedRequests[requestId] = timestamp
        while (acceptedRequests.size > capacity) {
            acceptedRequests.remove(acceptedRequests.entries.first().key)
        }
        return true
    }
}
