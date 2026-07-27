package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import java.util.UUID
import moe.chenxy.headphones.core.transport.GattNotificationMode
import moe.chenxy.headphones.core.transport.GattWriteMode
import moe.chenxy.headphones.core.session.DisconnectCause

/**
 * Android BluetoothGatt bridge using the memory-safe API 33+ value overloads.
 */
class AndroidGattClient(
    context: Context,
    private val device: BluetoothDevice,
) : GattClient {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter

    @Volatile
    private var callback: (GattCallbackEvent) -> Unit = {}

    @Volatile
    private var generationId: Long = -1

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var wasBondedAtConnect = false

    private val platformCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            val state = if (newState == BluetoothProfile.STATE_CONNECTED) {
                GattLinkState.CONNECTED
            } else {
                GattLinkState.DISCONNECTED
            }
            callback(
                GattCallbackEvent.ConnectionStateChanged(
                    generationId,
                    status,
                    state,
                    disconnectCause = when {
                        state == GattLinkState.CONNECTED -> DisconnectCause.LINK_LOST
                        adapter.state != BluetoothAdapter.STATE_ON ->
                            DisconnectCause.ADAPTER_OFF
                        wasBondedAtConnect &&
                            gatt.device.bondState == BluetoothDevice.BOND_NONE ->
                            DisconnectCause.BOND_REMOVED
                        else -> DisconnectCause.LINK_LOST
                    },
                ),
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            callback(GattCallbackEvent.MtuChanged(generationId, mtu, status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            callback(GattCallbackEvent.ServicesDiscovered(generationId, status))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            callback(
                GattCallbackEvent.CharacteristicRead(
                    generationId,
                    characteristic.uuid.toString(),
                    value.copyOf(),
                    status,
                ),
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            callback(
                GattCallbackEvent.CharacteristicWrite(
                    generationId,
                    characteristic.uuid.toString(),
                    status,
                ),
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            callback(
                GattCallbackEvent.DescriptorWrite(
                    generationId,
                    descriptor.characteristic.uuid.toString(),
                    descriptor.uuid.toString(),
                    status,
                ),
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            callback(
                GattCallbackEvent.Notification(
                    generationId,
                    characteristic.uuid.toString(),
                    value.copyOf(),
                ),
            )
        }
    }

    override fun setCallback(callback: (GattCallbackEvent) -> Unit) {
        this.callback = callback
    }

    @SuppressLint("MissingPermission", "NewApi")
    @Suppress("DEPRECATION")
    override fun connect(generationId: Long): GattStartResult {
        if (gatt != null) return GattStartResult.Rejected(detail = "GATT client already connected")
        this.generationId = generationId
        wasBondedAtConnect = device.bondState == BluetoothDevice.BOND_BONDED
        gatt = if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            device.connectGatt(settings, appContext.mainExecutor, platformCallback)
        } else {
            device.connectGatt(
                appContext,
                false,
                platformCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }
        return if (gatt != null) {
            GattStartResult.Started
        } else {
            GattStartResult.Rejected(detail = "connectGatt returned null")
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestMtu(mtu: Int): GattStartResult =
        booleanStart("requestMtu") { requireGatt().requestMtu(mtu) }

    @SuppressLint("MissingPermission")
    override fun discoverServices(): GattStartResult =
        booleanStart("discoverServices") { requireGatt().discoverServices() }

    @SuppressLint("MissingPermission")
    override fun hasService(serviceUuid: String): Boolean =
        requireGatt().getService(serviceUuid.asUuid()) != null

    @SuppressLint("MissingPermission")
    override fun hasCharacteristic(serviceUuid: String, characteristicUuid: String): Boolean =
        characteristic(serviceUuid, characteristicUuid) != null

    @SuppressLint("MissingPermission")
    override fun hasDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
    ): Boolean = characteristic(serviceUuid, characteristicUuid)
        ?.getDescriptor(descriptorUuid.asUuid()) != null

    @SuppressLint("MissingPermission")
    override fun setCharacteristicNotification(
        serviceUuid: String,
        characteristicUuid: String,
        enabled: Boolean,
    ): GattStartResult {
        val characteristic = characteristic(serviceUuid, characteristicUuid)
            ?: return missing("characteristic", characteristicUuid)
        return booleanStart("setCharacteristicNotification") {
            requireGatt().setCharacteristicNotification(characteristic, enabled)
        }
    }

    @SuppressLint("MissingPermission")
    override fun writeDescriptor(
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        mode: GattNotificationMode,
    ): GattStartResult {
        val descriptor = characteristic(serviceUuid, characteristicUuid)
            ?.getDescriptor(descriptorUuid.asUuid())
            ?: return missing("descriptor", descriptorUuid)
        val value = when (mode) {
            GattNotificationMode.NOTIFICATION ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            GattNotificationMode.INDICATION ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        return statusStart(
            "writeDescriptor",
            requireGatt().writeDescriptor(descriptor, value),
        )
    }

    @SuppressLint("MissingPermission")
    override fun readCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): GattStartResult {
        val characteristic = characteristic(serviceUuid, characteristicUuid)
            ?: return missing("characteristic", characteristicUuid)
        return booleanStart("readCharacteristic") {
            requireGatt().readCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    override fun writeCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        mode: GattWriteMode,
    ): GattStartResult {
        val characteristic = characteristic(serviceUuid, characteristicUuid)
            ?: return missing("characteristic", characteristicUuid)
        val writeType = when (mode) {
            GattWriteMode.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            GattWriteMode.WITHOUT_RESPONSE ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return statusStart(
            "writeCharacteristic",
            requireGatt().writeCharacteristic(characteristic, value, writeType),
        )
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        gatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        val old = gatt
        gatt = null
        old?.close()
    }

    private fun characteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ): BluetoothGattCharacteristic? = requireGatt()
        .getService(serviceUuid.asUuid())
        ?.getCharacteristic(characteristicUuid.asUuid())

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw IllegalStateException("BluetoothGatt is not connected")

    private inline fun booleanStart(
        operation: String,
        block: () -> Boolean,
    ): GattStartResult = if (block()) {
        GattStartResult.Started
    } else {
        GattStartResult.Rejected(detail = "$operation returned false")
    }

    private fun statusStart(operation: String, status: Int): GattStartResult =
        if (status == BluetoothStatusCodes.SUCCESS) {
            GattStartResult.Started
        } else {
            GattStartResult.Rejected(status, "$operation rejected by Bluetooth stack")
        }

    private fun missing(kind: String, uuid: String) =
        GattStartResult.Rejected(detail = "$kind $uuid is unavailable")
}

private fun String.asUuid(): UUID = UUID.fromString(this)
