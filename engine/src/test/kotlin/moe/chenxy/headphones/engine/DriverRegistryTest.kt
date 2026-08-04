package moe.chenxy.headphones.engine

import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneDriverProvider
import moe.chenxy.headphones.core.driver.HeadphoneSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRegistryTest {
    @Test
    fun `strongest inspection evidence wins`() {
        val hint = provider(VendorId.SONY, DetectionConfidence.HINT)
        val transport = provider(VendorId.OPPO, DetectionConfidence.TRANSPORT_EVIDENCE)
        val registry = DriverRegistry(listOf(hint, transport))

        assertEquals(VendorId.OPPO, registry.resolve(candidate())?.provider?.vendorId)
    }

    @Test
    fun `name hint alone cannot auto connect`() {
        val match = DriverRegistry(listOf(provider(VendorId.OPPO, DetectionConfidence.HINT)))
            .resolve(candidate())!!

        assertFalse(DriverRegistry.canAutoConnect(match))
    }

    @Test
    fun `transport evidence may auto connect`() {
        val match = DriverRegistry(
            listOf(provider(VendorId.OPPO, DetectionConfidence.TRANSPORT_EVIDENCE)),
        ).resolve(candidate())!!

        assertTrue(DriverRegistry.canAutoConnect(match))
    }

    @Test
    fun `bonded matching vendor may connect from protected profile event`() {
        val candidate = candidate(vendorId = VendorId.SONY, bonded = true)
        val match = DriverRegistry(listOf(provider(VendorId.SONY, DetectionConfidence.HINT)))
            .resolve(candidate)!!

        assertTrue(DriverRegistry.canConnectFromProfile(match, candidate))
    }

    @Test
    fun `profile event still rejects unbonded or mismatched vendor hints`() {
        val sony = provider(VendorId.SONY, DetectionConfidence.HINT)
        val unbonded = candidate(vendorId = VendorId.SONY, bonded = false)
        val mismatched = candidate(vendorId = VendorId.OPPO, bonded = true)

        assertFalse(
            DriverRegistry.canConnectFromProfile(DriverRegistry(listOf(sony)).resolve(unbonded)!!, unbonded),
        )
        assertFalse(
            DriverRegistry.canConnectFromProfile(DriverRegistry(listOf(sony)).resolve(mismatched)!!, mismatched),
        )
    }

    private fun provider(vendor: VendorId, confidence: DetectionConfidence) =
        object : HeadphoneDriverProvider {
            override val vendorId = vendor

            override fun inspect(candidate: DeviceCandidate) =
                DetectionEvidence(vendor, confidence, listOf("test"))

            override suspend fun createSession(context: DriverSessionContext): HeadphoneSession =
                error("not used")
        }

    private fun candidate(
        vendorId: VendorId? = null,
        bonded: Boolean = true,
    ) = DeviceCandidate(
        DeviceIdentity(DeviceId("test"), vendorId, "00"),
        "Headphones",
        bonded = bonded,
    )
}
