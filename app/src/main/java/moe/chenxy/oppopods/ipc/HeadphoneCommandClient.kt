package moe.chenxy.oppopods.ipc

import android.content.Context
import java.util.UUID
import moe.chenxy.headphones.core.operation.FeatureCommand
import moe.chenxy.oppopods.ui.state.HeadphoneUiStore

/** Brand-neutral command entry point shared by App UI and HyperOS adapters. */
object HeadphoneCommandClient {
    fun execute(
        context: Context,
        command: FeatureCommand,
        requestId: String = UUID.randomUUID().toString(),
    ): String {
        val current = HeadphoneUiStore.state.value
        context.sendBroadcast(
            HeadphoneIpcContract.commandIntent(
                command = IpcCommandPayload.from(command),
                requestId = requestId,
                deviceId = current.deviceId,
                vendorId = current.vendorId,
            ),
        )
        return requestId
    }
}
