package moe.chenxy.headphones.transport.android

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import moe.chenxy.headphones.core.session.DisconnectCause
import moe.chenxy.headphones.core.transport.GattNotificationMode
import moe.chenxy.headphones.core.transport.GattWriteMode

class FakeGattClient : GattClient {
    @Volatile
    private var callback: (GattCallbackEvent) -> Unit = {}

    @Volatile
    var generationId: Long = -1
        private set

    var connectStatus = GATT_STATUS_SUCCESS
    var mtuStatus = GATT_STATUS_SUCCESS
    var negotiatedMtu = 100
    var discoveryStatus = GATT_STATUS_SUCCESS
    var descriptorStatus = GATT_STATUS_SUCCESS
    var readStatus = GATT_STATUS_SUCCESS
    var writeStatus = GATT_STATUS_SUCCESS
    var readValue = byteArrayOf(1, 2, 3)
    var blockedOperation: String? = null
    var rejectedOperation: String? = null
    var permissionDeniedOperation: String? = null
    var servicePresent = true
    var txPresent = true
    var rxPresent = true
    var descriptorPresent = true
    val additionalCharacteristics = mutableSetOf<String>()

    val operations = CopyOnWriteArrayList<String>()
    val writes = CopyOnWriteArrayList<ByteArray>()
    val outstanding = CopyOnWriteArrayList<String>()
    val maxOutstanding = AtomicInteger(0)
    val disconnectCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override fun setCallback(callback: (GattCallbackEvent) -> Unit) {
        this.callback = callback
    }

    override fun connect(generationId: Long): GattStartResult {
        this.generationId = generationId
        return start(
            "connect",
            GattCallbackEvent.ConnectionStateChanged(
                generationId,
                connectStatus,
                if (connectStatus == GATT_STATUS_SUCCESS) {
                    GattLinkState.CONNECTED
                } else {
                    GattLinkState.DISCONNECTED
                },
            ),
        )
    }

    override fun requestMtu(mtu: Int): GattStartResult = start(
        "mtu",
        GattCallbackEvent.MtuChanged(generationId, negotiatedMtu, mtuStatus),
    )

    override fun discoverServices(): GattStartResult = start(
        "discover",
        GattCallbackEvent.ServicesDiscovered(generationId, discoveryStatus),
    )

    override fun hasService(serviceUuid: String): Boolean = servicePresent

    override fun hasCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): Boolean = when (characteristicUuid.lowercase()) {
        TX_UUID -> txPresent
        RX_UUID -> rxPresent
        else -> additionalCharacteristics.any {
            it.equals(characteristicUuid, ignoreCase = true)
        }
    }

    override fun hasDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
    ): Boolean = descriptorPresent

    override fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
    ): GattStartResult = immediate("setNotification")

    override fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        mode: GattNotificationMode,
    ): GattStartResult = start(
        "descriptor",
        GattCallbackEvent.DescriptorWrite(
            generationId,
            characteristicUuid,
            descriptorUuid,
            descriptorStatus,
        ),
    )

    override fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): GattStartResult = start(
        "read",
        GattCallbackEvent.CharacteristicRead(
            generationId,
            characteristicUuid,
            readValue.copyOf(),
            readStatus,
        ),
    )

    override fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        mode: GattWriteMode,
    ): GattStartResult {
        writes += value.copyOf()
        return if (mode == GattWriteMode.WITHOUT_RESPONSE) {
            immediate("writeWithoutResponse")
        } else {
            start(
                "write",
                GattCallbackEvent.CharacteristicWrite(
                    generationId,
                    characteristicUuid,
                    writeStatus,
                ),
            )
        }
    }

    override fun disconnect() {
        disconnectCount.incrementAndGet()
    }

    override fun close() {
        closeCount.incrementAndGet()
    }

    fun emit(event: GattCallbackEvent) {
        val operation = event.operationName
        if (operation != null) outstanding.remove(operation)
        callback(event)
    }

    fun emitWrite(status: Int = GATT_STATUS_SUCCESS, generation: Long = generationId) {
        emit(GattCallbackEvent.CharacteristicWrite(generation, TX_UUID, status))
    }

    fun emitDisconnect(
        status: Int = 8,
        generation: Long = generationId,
        cause: DisconnectCause = DisconnectCause.LINK_LOST,
    ) {
        emit(
            GattCallbackEvent.ConnectionStateChanged(
                generation,
                status,
                GattLinkState.DISCONNECTED,
                cause,
            ),
        )
    }

    fun notify(value: ByteArray, generation: Long = generationId) {
        emit(GattCallbackEvent.Notification(generation, RX_UUID, value.copyOf()))
    }

    private fun immediate(operation: String): GattStartResult {
        beforeStart(operation)
        operations += operation
        return if (rejectedOperation == operation) {
            GattStartResult.Rejected(201, "$operation rejected")
        } else {
            GattStartResult.Started
        }
    }

    private fun start(operation: String, event: GattCallbackEvent): GattStartResult {
        beforeStart(operation)
        operations += operation
        if (rejectedOperation == operation) {
            return GattStartResult.Rejected(201, "$operation rejected")
        }
        outstanding += operation
        maxOutstanding.updateAndGet { maxOf(it, outstanding.size) }
        if (blockedOperation != operation) emit(event)
        return GattStartResult.Started
    }

    private fun beforeStart(operation: String) {
        if (permissionDeniedOperation == operation) {
            throw SecurityException("$operation denied")
        }
    }

    private val GattCallbackEvent.operationName: String?
        get() = when (this) {
            is GattCallbackEvent.ConnectionStateChanged -> "connect"
            is GattCallbackEvent.MtuChanged -> "mtu"
            is GattCallbackEvent.ServicesDiscovered -> "discover"
            is GattCallbackEvent.CharacteristicRead -> "read"
            is GattCallbackEvent.CharacteristicWrite -> "write"
            is GattCallbackEvent.DescriptorWrite -> "descriptor"
            is GattCallbackEvent.Notification -> null
        }

    companion object {
        const val SERVICE_UUID = "00000000-0000-0000-0000-000000000000"
        const val TX_UUID = "00000000-0000-0000-0000-000000000001"
        const val RX_UUID = "00000000-0000-0000-0000-000000000002"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
