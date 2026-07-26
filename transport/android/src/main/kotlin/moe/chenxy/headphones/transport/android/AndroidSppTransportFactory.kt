package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.UUID
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec

/**
 * Binds a platform BluetoothDevice to the vendor-neutral transport factory.
 *
 * The UUID still comes from the driver through [TransportSpec]; this class only
 * owns Android socket construction.
 */
class AndroidSppTransportFactory(
    private val device: BluetoothDevice,
) : TransportFactory {
    @SuppressLint("MissingPermission")
    override suspend fun create(device: DeviceIdentity, spec: TransportSpec): ByteTransport {
        require(device.primaryAddress.equals(this.device.address, ignoreCase = true)) {
            "BluetoothDevice address does not match session identity"
        }
        require(spec is TransportSpec.Spp) { "AndroidSppTransportFactory only supports SPP" }
        val uuid = UUID.fromString(spec.serviceUuid)
        return SppTransport(AndroidSppSocket.factory(this.device, uuid, spec.secure))
    }
}
