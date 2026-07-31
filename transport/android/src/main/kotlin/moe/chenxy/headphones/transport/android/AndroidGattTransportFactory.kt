package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec

/**
 * Binds one Android BluetoothDevice to vendor-supplied GATT UUIDs and policy.
 */
class AndroidGattTransportFactory(
    context: Context,
    private val platformDevice: BluetoothDevice,
    private val sessionIdentityAddress: String = platformDevice.address,
) : TransportFactory {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun create(device: DeviceIdentity, spec: TransportSpec): ByteTransport {
        requireMatchingGattSessionIdentity(device, sessionIdentityAddress)
        require(spec is TransportSpec.Gatt) {
            "AndroidGattTransportFactory only supports BLE GATT"
        }
        return GattTransport(spec, GattClientFactory {
            AndroidGattClient(appContext, platformDevice)
        })
    }
}

/**
 * Keeps the logical session identity tied to the picker device even when the
 * physical GATT endpoint is another currently connected member of its LE Audio
 * coordinated set.
 */
internal fun requireMatchingGattSessionIdentity(
    device: DeviceIdentity,
    sessionIdentityAddress: String,
) {
    require(device.primaryAddress.equals(sessionIdentityAddress, ignoreCase = true)) {
        "BluetoothDevice address does not match session identity"
    }
}
