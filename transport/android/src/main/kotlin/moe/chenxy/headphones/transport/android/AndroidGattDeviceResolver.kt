package moe.chenxy.headphones.transport.android

import android.bluetooth.BluetoothDevice
import moe.chenxy.headphones.core.transport.TransportSpec

/** Allows the Android composition root to resolve a vendor-advertised GATT endpoint. */
fun interface AndroidGattDeviceResolver {
    suspend fun resolve(
        connectedDevices: List<BluetoothDevice>,
        spec: TransportSpec.Gatt,
    ): BluetoothDevice?

    companion object {
        val DIRECT = AndroidGattDeviceResolver { connectedDevices, _ ->
            connectedDevices.firstOrNull()
        }
    }
}
