package moe.chenxy.oppopods.pods

enum class RfcommConnectionState(val wireValue: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    ERROR("error");

    companion object {
        fun fromWireValue(value: String): RfcommConnectionState =
            entries.firstOrNull { it.wireValue == value } ?: ERROR
    }
}

fun interface RfcommConnectionStateObserver {
    fun onConnectionStateChanged(state: RfcommConnectionState)
}

interface RfcommConnectionStateSource {
    val currentState: RfcommConnectionState

    fun addObserver(observer: RfcommConnectionStateObserver, emitCurrent: Boolean = true)

    fun removeObserver(observer: RfcommConnectionStateObserver)
}

/** Small Android-free observable used by controller tests and future adapters. */
class RfcommConnectionStateObservable(
    initialState: RfcommConnectionState = RfcommConnectionState.DISCONNECTED,
) : RfcommConnectionStateSource {
    private val lock = Any()
    private val observers = linkedSetOf<RfcommConnectionStateObserver>()

    @Volatile
    override var currentState: RfcommConnectionState = initialState
        private set

    override fun addObserver(observer: RfcommConnectionStateObserver, emitCurrent: Boolean) {
        synchronized(lock) {
            observers += observer
            if (emitCurrent) observer.onConnectionStateChanged(currentState)
        }
    }

    override fun removeObserver(observer: RfcommConnectionStateObserver) {
        synchronized(lock) {
            observers -= observer
        }
    }

    fun publish(state: RfcommConnectionState) {
        synchronized(lock) {
            if (currentState == state) return
            currentState = state
            observers.toList().forEach { it.onConnectionStateChanged(state) }
        }
    }
}

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
