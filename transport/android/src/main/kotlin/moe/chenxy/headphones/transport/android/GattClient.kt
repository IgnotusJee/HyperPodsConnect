package moe.chenxy.headphones.transport.android

import moe.chenxy.headphones.core.transport.GattNotificationMode
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.session.DisconnectCause

/**
 * Small platform port around BluetoothGatt.
 *
 * Keeping UUIDs as strings and callbacks as immutable values makes the queue
 * and transport state machine testable on the JVM without Android framework
 * objects. Vendor UUIDs enter only through TransportSpec.Gatt.
 */
interface GattClient {
    fun setCallback(callback: (GattCallbackEvent) -> Unit)

    fun connect(generationId: Long): GattStartResult

    fun requestMtu(mtu: Int): GattStartResult

    fun discoverServices(): GattStartResult

    fun hasService(serviceUuid: String): Boolean

    fun hasCharacteristic(serviceUuid: String, characteristicUuid: String): Boolean

    fun hasDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
    ): Boolean

    fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
    ): GattStartResult

    fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        mode: GattNotificationMode,
    ): GattStartResult

    fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): GattStartResult

    fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        mode: GattWriteMode,
    ): GattStartResult

    fun disconnect()

    fun close()
}

fun interface GattClientFactory {
    fun create(): GattClient
}

sealed interface GattStartResult {
    data object Started : GattStartResult

    data class Rejected(
        val status: Int? = null,
        val detail: String? = null,
    ) : GattStartResult
}

enum class GattLinkState { CONNECTED, DISCONNECTED }

sealed interface GattCallbackEvent {
    val generationId: Long

    data class ConnectionStateChanged(
        override val generationId: Long,
        val status: Int,
        val state: GattLinkState,
        val disconnectCause: DisconnectCause = DisconnectCause.LINK_LOST,
    ) : GattCallbackEvent

    data class MtuChanged(
        override val generationId: Long,
        val mtu: Int,
        val status: Int,
    ) : GattCallbackEvent

    data class ServicesDiscovered(
        override val generationId: Long,
        val status: Int,
    ) : GattCallbackEvent

    data class CharacteristicRead(
        override val generationId: Long,
        val characteristicUuid: String,
        val value: ByteArray,
        val status: Int,
    ) : GattCallbackEvent

    data class CharacteristicWrite(
        override val generationId: Long,
        val characteristicUuid: String,
        val status: Int,
    ) : GattCallbackEvent

    data class DescriptorWrite(
        override val generationId: Long,
        val characteristicUuid: String,
        val descriptorUuid: String,
        val status: Int,
    ) : GattCallbackEvent

    data class Notification(
        override val generationId: Long,
        val characteristicUuid: String,
        val value: ByteArray,
    ) : GattCallbackEvent
}

internal const val GATT_STATUS_SUCCESS = 0
