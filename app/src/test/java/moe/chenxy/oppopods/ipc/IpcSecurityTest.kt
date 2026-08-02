package moe.chenxy.oppopods.ipc

import moe.chenxy.oppopods.utils.miuiStrongToast.data.LegacyPodsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpcSecurityTest {
    @Test
    fun `sender policy fails closed and uses action-specific allowlists`() {
        assertFalse(IpcSenderPolicy.isAllowed(null, IpcSenderPolicy.commandSenders))
        assertFalse(IpcSenderPolicy.isAllowed("com.example.attacker", IpcSenderPolicy.commandSenders))
        assertTrue(
            IpcSenderPolicy.isAllowed(
                HeadphoneIpcContract.SETTINGS_PACKAGE,
                IpcSenderPolicy.commandSenders,
            ),
        )
        assertTrue(
            IpcSenderPolicy.isAllowed(
                HeadphoneIpcContract.MODULE_PACKAGE,
                IpcSenderPolicy.allowedBluetoothLegacySenders(
                    LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND,
                ),
            ),
        )
        assertFalse(
            IpcSenderPolicy.isAllowed(
                HeadphoneIpcContract.XIAOMI_BLUETOOTH_PACKAGE,
                IpcSenderPolicy.allowedBluetoothLegacySenders(
                    LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND,
                ),
            ),
        )
        assertTrue(
            IpcSenderPolicy.isAllowed(
                HeadphoneIpcContract.XIAOMI_BLUETOOTH_PACKAGE,
                IpcSenderPolicy.allowedBluetoothLegacySenders(LegacyPodsAction.ACTION_CYCLE_ANC),
            ),
        )
    }

    @Test
    fun `every bluetooth legacy control action has the minimum sender set`() {
        assertEquals(
            IpcSenderPolicy.uiInitSenders,
            IpcSenderPolicy.allowedBluetoothLegacySenders(LegacyPodsAction.ACTION_PODS_UI_INIT),
        )
        val moduleActions = listOf(
            LegacyPodsAction.ACTION_CONNECT_POD_REQUEST,
            LegacyPodsAction.ACTION_DISCONNECT_POD_REQUEST,
            LegacyPodsAction.ACTION_PODS_UI_CLOSED,
            LegacyPodsAction.ACTION_AUTO_GAME_MODE_CHANGED,
            LegacyPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED,
            LegacyPodsAction.ACTION_CONFIG_CHANGED,
            LegacyPodsAction.ACTION_RFCOMM_LOG_CONNECT,
            LegacyPodsAction.ACTION_RFCOMM_LOG_DISCONNECT,
            LegacyPodsAction.ACTION_RFCOMM_LOG_CLEAR,
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_UNLOCK,
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_LOCK,
            LegacyPodsAction.ACTION_RFCOMM_DEBUG_SEND,
        )
        moduleActions.forEach { action ->
            assertEquals(IpcSenderPolicy.moduleOnly, IpcSenderPolicy.allowedBluetoothLegacySenders(action))
        }
        assertEquals(
            IpcSenderPolicy.xiaomiBluetoothOnly,
            IpcSenderPolicy.allowedBluetoothLegacySenders(LegacyPodsAction.ACTION_CYCLE_ANC),
        )
        assertTrue(IpcSenderPolicy.allowedBluetoothLegacySenders("unknown").isEmpty())
        assertEquals(HeadphoneIpcContract.eventTargets.toSet(), IpcSenderPolicy.commandSenders)
    }

    @Test
    fun `replay guard rejects stale future duplicate and evicted requests`() {
        var now = 100_000L
        val guard = IpcReplayGuard(clock = { now }, capacity = 2)

        assertFalse(guard.accept("stale", 69_999L))
        assertFalse(guard.accept("future", 105_001L))
        assertTrue(guard.accept("one", now))
        assertFalse(guard.accept("one", now))
        assertTrue(guard.accept("two", now))
        assertTrue(guard.accept("three", now))
        assertTrue(guard.accept("one", now))
        now += 30_001L
        assertFalse(guard.accept("too-old", 100_000L))
    }

    @Test
    fun `mutating command requires exact active device and vendor`() {
        val valid = IpcCommandEnvelope(
            requestId = "request",
            deviceId = "device-1",
            vendorId = "OPPO",
            timestamp = 1,
            payload = IpcCommandPayload(HeadphoneIpcContract.TYPE_REFRESH_ALL),
        )

        assertTrue(IpcCommandValidator.targetsCurrentSession(valid, "device-1", "OPPO"))
        assertFalse(IpcCommandValidator.targetsCurrentSession(valid.copy(deviceId = null), "device-1", "OPPO"))
        assertFalse(IpcCommandValidator.targetsCurrentSession(valid.copy(vendorId = null), "device-1", "OPPO"))
        assertFalse(IpcCommandValidator.targetsCurrentSession(valid, "device-2", "OPPO"))
        assertFalse(IpcCommandValidator.targetsCurrentSession(valid, "device-1", "SONY"))
    }
}
