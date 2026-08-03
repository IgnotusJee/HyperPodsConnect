package moe.chenxy.oppopods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootManagerRestartTest {
    @Test
    fun `persistent Bluetooth scopes are killed by process name`() {
        assertEquals(
            "status=0; " +
                "(if pidof com.android.bluetooth >/dev/null 2>&1; " +
                "then killall com.android.bluetooth; fi) || status=1; " +
                "(if pidof com.xiaomi.bluetooth >/dev/null 2>&1; " +
                "then killall com.xiaomi.bluetooth; fi) || status=1; " +
                "exit \$status",
            RootManager.buildRestartCommand(
                listOf("com.android.bluetooth", "com.xiaomi.bluetooth"),
            ),
        )
    }

    @Test
    fun `regular scopes use package force stop and invalid names are rejected`() {
        assertEquals(
            "status=0; (am force-stop com.milink.service) || status=1; exit \$status",
            RootManager.buildRestartCommand(
                listOf("com.milink.service", "com.milink.service", "bad;command"),
            ),
        )
        assertNull(RootManager.buildRestartCommand(listOf("bad;command")))
    }
}
