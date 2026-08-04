package org.hyperpods.connect.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsProcessRouterTest {
    @Test
    fun `routes only verified HyperOS host processes`() {
        assertEquals(
            setOf(HyperOsHookTarget.ANDROID_BLUETOOTH),
            HyperOsProcessRouter.targets("com.android.bluetooth", "com.android.bluetooth"),
        )
        assertEquals(
            setOf(HyperOsHookTarget.XIAOMI_BLUETOOTH),
            HyperOsProcessRouter.targets("com.xiaomi.bluetooth", "com.xiaomi.bluetooth"),
        )
        assertEquals(
            setOf(HyperOsHookTarget.SETTINGS),
            HyperOsProcessRouter.targets("com.android.settings", "com.android.settings"),
        )
        assertEquals(
            setOf(HyperOsHookTarget.MILINK_CORE),
            HyperOsProcessRouter.targets("com.milink.service", "com.milink.service:core"),
        )
        assertEquals(
            setOf(HyperOsHookTarget.MILINK_UI),
            HyperOsProcessRouter.targets("com.milink.service", "com.milink.service:ui"),
        )
    }

    @Test
    fun `remote and unrelated processes fail closed`() {
        assertTrue(
            HyperOsProcessRouter.targets(
                "com.android.settings",
                "com.android.settings:remote",
            ).isEmpty(),
        )
        assertTrue(
            HyperOsProcessRouter.targets(
                "com.milink.service",
                "com.milink.service:provider",
            ).isEmpty(),
        )
        assertTrue(
            HyperOsProcessRouter.targets(
                "com.milink.service",
                "com.milink.service:audio",
            ).isEmpty(),
        )
    }

    @Test
    fun `contract probing reports only the missing group requirements`() {
        val core = HyperOsMethodContract("ProfileContext", "getDeviceId", emptyList())
        val spatial = HyperOsMethodContract("ProfileContext", "setAudioEffectState", emptyList())

        assertTrue(HyperOsContractProbe.missing(listOf(core)) { true }.isEmpty())
        assertEquals(
            listOf(spatial),
            HyperOsContractProbe.missing(listOf(spatial)) { false },
        )
    }
}
