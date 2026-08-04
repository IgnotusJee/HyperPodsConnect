package moe.chenxy.oppopods.runtime.bluetoothprocess

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyAutoPlayGattDeviceResolverTest {
    @Test
    fun `prefers dual Sony identity over rotating BLE group lead`() {
        assertEquals(
            1,
            selectSonyFallbackIndex(
                listOf(BluetoothDevice.DEVICE_TYPE_LE, BluetoothDevice.DEVICE_TYPE_DUAL),
            ),
        )
        assertEquals(
            0,
            selectSonyFallbackIndex(listOf(BluetoothDevice.DEVICE_TYPE_LE)),
        )
    }

    @Test
    fun `matches official Sony discovery advertisement to either LE Audio member`() {
        val advertisement = ByteArray(17).apply {
            this[0] = 0x13
            this[1] = 0x00
            this[3] = 0x70
            this[4] = 0xA1.toByte()
        }

        assertTrue(
            isSonyAutoPlayAdvertisement(
                advertisement,
                setOf(
                    sonyAutoPlayUniqueId("F8:4E:17:D1:32:27"),
                    sonyAutoPlayUniqueId("CF:C8:EC:C1:67:14"),
                ),
            ),
        )
        assertFalse(isSonyAutoPlayAdvertisement(advertisement, setOf(0x1234)))
    }

    @Test
    fun `uses the same upper-case SHA-1 prefix as Sony discovery`() {
        assertTrue(sonyAutoPlayUniqueId("f8:4e:17:d1:32:27") == 0xB06C)
        assertTrue(sonyAutoPlayUniqueId("cf:c8:ec:c1:67:14") == 0x70A1)
    }
}
