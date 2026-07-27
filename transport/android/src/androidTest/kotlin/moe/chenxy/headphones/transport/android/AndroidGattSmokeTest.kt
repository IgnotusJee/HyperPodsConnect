package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.core.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke test: verifies the Android Bluetooth classes can construct the
 * real GATT transport boundary without opening or probing an arbitrary device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidGattSmokeTest {
    @SuppressLint("MissingPermission")
    @Test
    fun realAndroidFactoryCreatesClosedVendorNeutralGattTransport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        )
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assumeTrue(adapter != null)
        val address = "02:00:00:00:00:01"
        val device = adapter!!.getRemoteDevice(address)
        val identity = DeviceIdentity(DeviceId.fromAddress(address), null, address)
        val spec = TransportSpec.Gatt(
            serviceUuid = "00000000-0000-0000-0000-000000000000",
            txCharacteristicUuid = "00000000-0000-0000-0000-000000000001",
            rxCharacteristicUuid = "00000000-0000-0000-0000-000000000002",
            cccdUuid = "00002902-0000-1000-8000-00805f9b34fb",
        )

        val transport = AndroidGattTransportFactory(context, device).create(identity, spec)

        assertEquals(TransportKind.BLE_GATT, transport.kind)
        assertEquals(TransportState.Closed, transport.state.value)
        assertEquals(20, transport.maxWriteSize.value)
        assertTrue(
            transport is GattTransport,
        )
    }
}
