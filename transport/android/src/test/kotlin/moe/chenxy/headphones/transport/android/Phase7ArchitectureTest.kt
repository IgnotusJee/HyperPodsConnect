package moe.chenxy.headphones.transport.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Phase7ArchitectureTest {
    private fun source(relative: String): String? {
        val paths = listOf(
            File(relative),
            File("transport/android/$relative"),
            File("../$relative"),
            File("../../transport/android/$relative"),
        )
        return paths.firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun `GATT transport contains no vendor constants`() {
        val files = listOf(
            "src/main/kotlin/moe/chenxy/headphones/transport/android/GattClient.kt",
            "src/main/kotlin/moe/chenxy/headphones/transport/android/GattOperationQueue.kt",
            "src/main/kotlin/moe/chenxy/headphones/transport/android/GattTransport.kt",
            "src/main/kotlin/moe/chenxy/headphones/transport/android/AndroidGattClient.kt",
        )
        files.forEach { path ->
            val value = source(path)
            assumeTrue(value != null)
            assertFalse(value!!.contains("sony", ignoreCase = true))
            assertFalse(value.contains("oppo", ignoreCase = true))
        }
    }

    @Test
    fun `all callback backed operations share one serial queue`() {
        val transport = source(
            "src/main/kotlin/moe/chenxy/headphones/transport/android/GattTransport.kt",
        )
        assumeTrue(transport != null)

        assertTrue(transport!!.contains("GattOperationQueue"))
        assertTrue(transport.contains("operation = \"requestMtu\""))
        assertTrue(transport.contains("operation = \"discoverServices\""))
        assertTrue(transport.contains("operation = \"writeCccd\""))
        assertTrue(transport.contains("operation = \"readCharacteristic\""))
        assertTrue(transport.contains("operation = \"writeCharacteristic\""))
    }

    @Test
    fun `Android bridge uses memory safe value APIs and API 37 connection settings`() {
        val client = source(
            "src/main/kotlin/moe/chenxy/headphones/transport/android/AndroidGattClient.kt",
        )
        assumeTrue(client != null)

        assertTrue(client!!.contains("BluetoothGattConnectionSettings.Builder()"))
        assertTrue(client.contains("writeCharacteristic(characteristic, value, writeType)"))
        assertTrue(client.contains("writeDescriptor(descriptor, value)"))
        assertTrue(client.contains("value: ByteArray"))
        assertFalse(client.contains("descriptor.value ="))
        assertFalse(client.contains("characteristic.value ="))
    }
}
