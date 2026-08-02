package moe.chenxy.oppopods.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.chenxy.oppopods.ipc.BatteryPayload
import moe.chenxy.oppopods.ipc.CapabilityPayload
import moe.chenxy.oppopods.ipc.FeatureValuePayload
import moe.chenxy.oppopods.ipc.HeadphoneSnapshotPayload
import moe.chenxy.oppopods.ipc.IpcConnectionState
import moe.chenxy.oppopods.ipc.OperationPayload

enum class UiConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

enum class UiOperationStatus { PENDING, CONFIRMED, TIMED_OUT, FAILED, CANCELLED }

data class UiFeatureOption(
    val value: String,
    val label: String,
)

data class UiOperation(
    val requestId: String,
    val featureId: String,
    val status: UiOperationStatus,
    val phase: String,
    val failure: String?,
    val detail: String?,
)

data class UiFeatureState(
    val id: String,
    val readable: Boolean,
    val writable: Boolean,
    val confirmed: String?,
    val pending: String?,
    val stale: Boolean,
    val options: List<UiFeatureOption>,
    val operation: UiOperation?,
) {
    val visible: Boolean get() = readable
    val displayed: String? get() = pending ?: confirmed
    val readOnly: Boolean get() = visible && !writable
}

data class HeadphoneUiState(
    val hostInstanceId: String? = null,
    val deviceId: String? = null,
    val generationId: Long = -1,
    val emittedAtMillis: Long = -1,
    val vendorId: String? = null,
    val address: String? = null,
    val title: String = "",
    val connection: UiConnectionState = UiConnectionState.DISCONNECTED,
    val topology: String? = null,
    val firmware: String? = null,
    val compatibility: String? = null,
    val batteries: Map<String, BatteryPayload> = emptyMap(),
    val wearing: Map<String, String> = emptyMap(),
    val noiseControlActiveMode: String? = null,
    val features: Map<String, UiFeatureState> = emptyMap(),
    val lastOperation: UiOperation? = null,
) {
    val connected: Boolean get() = connection == UiConnectionState.CONNECTED

    fun feature(id: String): UiFeatureState? = features[id]?.takeIf(UiFeatureState::visible)

    fun supportsAddress(value: String?): Boolean =
        value != null && address?.equals(value, ignoreCase = true) == true
}

class HeadphoneUiStateStore {
    private val mutableState = MutableStateFlow(HeadphoneUiState())
    val state: StateFlow<HeadphoneUiState> = mutableState.asStateFlow()

    fun accept(snapshot: HeadphoneSnapshotPayload): Boolean {
        val current = mutableState.value
        if (
            current.hostInstanceId == snapshot.hostInstanceId &&
            (
                snapshot.generationId < current.generationId ||
                    (
                        snapshot.generationId == current.generationId &&
                            snapshot.emittedAtMillis <= current.emittedAtMillis
                        )
                )
        ) return false

        val operation = snapshot.operation?.toUiOperation()
        val capabilities = snapshot.capabilities.associateBy(CapabilityPayload::featureId)
        val features = capabilities.mapValues { (id, capability) ->
            val value = snapshot.features[id] ?: FeatureValuePayload(
                confirmed = null,
                pending = null,
                stale = false,
                source = null,
            )
            capability.toUiFeature(
                value,
                operation?.takeIf { it.featureId == id },
            )
        }
        mutableState.value = HeadphoneUiState(
            hostInstanceId = snapshot.hostInstanceId,
            deviceId = snapshot.deviceId,
            generationId = snapshot.generationId,
            emittedAtMillis = snapshot.emittedAtMillis,
            vendorId = snapshot.vendorId,
            address = snapshot.primaryAddress,
            title = snapshot.deviceName.orEmpty(),
            connection = snapshot.toConnectionState(),
            topology = snapshot.topology,
            firmware = snapshot.firmware,
            compatibility = snapshot.compatibility,
            batteries = snapshot.batteries.associateBy(BatteryPayload::component),
            wearing = snapshot.wearing,
            noiseControlActiveMode = snapshot.noiseControlActiveMode,
            features = features,
            lastOperation = operation,
        )
        return true
    }

    fun clear() {
        mutableState.value = HeadphoneUiState()
    }
}

object HeadphoneUiStore {
    private val store = HeadphoneUiStateStore()
    val state: StateFlow<HeadphoneUiState> get() = store.state

    fun accept(snapshot: HeadphoneSnapshotPayload): Boolean = store.accept(snapshot)

    fun clear() = store.clear()

    fun supports(address: String?): Boolean = state.value.supportsAddress(address)
}

private fun HeadphoneSnapshotPayload.toConnectionState(): UiConnectionState = when {
    connection == IpcConnectionState.IDLE -> UiConnectionState.DISCONNECTED
    connection == IpcConnectionState.FAILED -> UiConnectionState.ERROR
    connection == IpcConnectionState.READY && protocolReady -> UiConnectionState.CONNECTED
    else -> UiConnectionState.CONNECTING
}

private fun CapabilityPayload.toUiFeature(
    value: FeatureValuePayload,
    operation: UiOperation?,
): UiFeatureState {
    val readable = canRead && evidence != "REFUTED"
    val writable = canWrite && evidence in setOf("ADVERTISED", "VERIFIED")
    return UiFeatureState(
        id = featureId,
        readable = readable,
        writable = writable,
        confirmed = value.confirmed,
        pending = value.pending,
        stale = value.stale,
        options = allowedValues.map { option ->
            UiFeatureOption(option, valueLabels[option] ?: option.humanize())
        },
        operation = operation,
    )
}

private fun OperationPayload.toUiOperation(): UiOperation = UiOperation(
    requestId = requestId,
    featureId = featureId,
    status = when (phase) {
        "STATE_CONFIRMED", "READ_BACK_CONFIRMED" -> UiOperationStatus.CONFIRMED
        "TIMED_OUT" -> UiOperationStatus.TIMED_OUT
        "FAILED" -> UiOperationStatus.FAILED
        "CANCELLED" -> UiOperationStatus.CANCELLED
        else -> UiOperationStatus.PENDING
    },
    phase = phase,
    failure = failure,
    detail = detail,
)

private fun String.humanize(): String =
    substringAfterLast(':').lowercase().replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
