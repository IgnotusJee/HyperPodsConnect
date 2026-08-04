package moe.chenxy.headphones.transport.android

import android.bluetooth.BluetoothDevice
import android.content.Context
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.transport.ByteTransport
import moe.chenxy.headphones.core.transport.TransportFactory
import moe.chenxy.headphones.core.transport.TransportSpec

/**
 * Android composition factory for a driver that can select SPP or GATT.
 *
 * Selection remains entirely in the vendor driver; this class only dispatches
 * an already validated [TransportSpec] to the matching platform adapter.
 */
class AndroidBluetoothTransportFactory(
    context: Context,
    private val platformDevice: BluetoothDevice,
    private val gattDeviceResolver: AndroidGattDeviceResolver = AndroidGattDeviceResolver.DIRECT,
) : TransportFactory {
    private val spp = AndroidSppTransportFactory(platformDevice)
    private val appContext = context.applicationContext
    private val leAudioGroupResolver = AndroidLeAudioGroupResolver(appContext)

    override suspend fun create(
        device: DeviceIdentity,
        spec: TransportSpec,
    ): ByteTransport = when (spec) {
        is TransportSpec.Spp -> spp.create(device, spec)
        is TransportSpec.Gatt -> {
            val connectedGroupMembers =
                leAudioGroupResolver.resolveConnectedGroupMembers(platformDevice)
            val endpoint = gattDeviceResolver.resolve(connectedGroupMembers, spec)
                ?: connectedGroupMembers.first()
            AndroidGattTransportFactory(
                context = appContext,
                platformDevice = endpoint,
                sessionIdentityAddress = platformDevice.address,
            ).create(device, spec)
        }
    }
}
