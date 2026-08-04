package moe.chenxy.oppopods.utils

import moe.chenxy.oppopods.config.PodImageResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RootManagerArtworkTest {
    @Test
    fun `LinkBuds S resolves the official image matching device color`() {
        val candidate = RootManager.resolveSonyOfficialNetworkCandidate(
            deviceName = "LinkBuds S",
            colorId = "0x05",
        )

        assertNotNull(candidate)
        assertEquals("0x32:0x06", candidate?.selector?.productId)
        assertEquals("0x05", candidate?.selector?.colorId)
        assertEquals(
            "https://hpc-image.data-gateway.seeds.services/76e4e03e-b1ce-4449-b9d5-1ebe86db9371.png",
            candidate?.resourceUrls?.get(PodImageResource.DETAIL),
        )
    }

    @Test
    fun `XM4 has a strict Sony official network fallback`() {
        val candidate = RootManager.resolveSonyOfficialNetworkCandidate(" wh-1000xm4 ")

        assertNotNull(candidate)
        assertEquals("0x31:0x05", candidate?.selector?.productId)
        assertEquals(
            "https://hpc-image.data-gateway.seeds.services/8ea91f30-6237-4b4e-b261-f26a9d199cf7.png",
            candidate?.resourceUrls?.get(PodImageResource.DETAIL),
        )
        assertNull(RootManager.resolveSonyOfficialNetworkCandidate("WH-1000XM4 lookalike"))
    }

}
