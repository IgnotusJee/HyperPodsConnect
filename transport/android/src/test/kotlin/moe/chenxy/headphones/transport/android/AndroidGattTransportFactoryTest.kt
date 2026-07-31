package moe.chenxy.headphones.transport.android

import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.VendorId
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGattTransportFactoryTest {
    @Test
    fun `session identity accepts the selected member independently of physical group lead`() {
        requireMatchingGattSessionIdentity(
            device = identity("02:00:00:00:00:AA"),
            sessionIdentityAddress = "02:00:00:00:00:aa",
        )
    }

    @Test
    fun `session identity still rejects an unrelated selected device`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireMatchingGattSessionIdentity(
                device = identity("02:00:00:00:00:AA"),
                sessionIdentityAddress = "02:00:00:00:00:BB",
            )
        }
    }

    private fun identity(address: String) = DeviceIdentity(
        id = DeviceId.fromAddress(address),
        vendorId = VendorId.SONY,
        primaryAddress = address,
    )
}
