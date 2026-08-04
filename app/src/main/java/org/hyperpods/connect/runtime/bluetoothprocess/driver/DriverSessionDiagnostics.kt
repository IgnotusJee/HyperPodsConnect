package org.hyperpods.connect.runtime.bluetoothprocess.driver

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.protocol.oppo.session.OppoSession
import moe.chenxy.headphones.protocol.oppo.session.OppoSessionEvent
import moe.chenxy.headphones.protocol.sony.frame.TandemCodec
import moe.chenxy.headphones.protocol.sony.message.SonyCommand
import moe.chenxy.headphones.protocol.sony.session.SonySession
import moe.chenxy.headphones.protocol.sony.session.SonySessionEvent
import org.hyperpods.connect.pods.RfcommLog

/** Vendor diagnostics kept beside the driver catalog, outside presentation code. */
object DriverSessionDiagnostics {
    suspend fun sendDebugFrame(session: HeadphoneSession?, bytes: ByteArray): Boolean {
        val vendorSession = session as? OppoSession ?: return false
        vendorSession.sendDebugFrame(bytes)
        return true
    }

    fun attach(
        context: Context,
        session: HeadphoneSession?,
        scope: CoroutineScope,
        generationId: Long,
        isCurrentGeneration: () -> Boolean,
    ): Job? = when (session) {
        is OppoSession -> scope.launch {
            session.events.collect { event ->
                if (!isCurrentGeneration()) return@collect
                logOppo(context, event)
            }
        }
        is SonySession -> scope.launch {
            session.events.collect { event ->
                if (!isCurrentGeneration()) return@collect
                logSony(event)
            }
        }
        else -> null
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun logOppo(context: Context, event: OppoSessionEvent) {
        when (event) {
            is OppoSessionEvent.TxFrame ->
                RfcommLog.d(context, "RFCOMM/TX", event.bytes.toHexString(HexFormat.UpperCase))
            is OppoSessionEvent.RawChunk ->
                RfcommLog.d(context, "RFCOMM/RX", event.bytes.toHexString(HexFormat.UpperCase))
            is OppoSessionEvent.UnknownMessage ->
                Log.d("HyperPodsConnect-Driver", "Unknown driver message: ${event.message}")
            is OppoSessionEvent.WearReport,
            is OppoSessionEvent.Message -> Unit
        }
    }

    private fun logSony(event: SonySessionEvent) {
        when (event) {
            is SonySessionEvent.TxFrame ->
                Log.d("HyperPodsConnect-Driver", "TX ${event.bytes.toLogHex()}")
            is SonySessionEvent.RxChunk -> Unit
            is SonySessionEvent.RxFrame -> {
                val command = event.frame.payload.firstOrNull()?.toInt()?.and(0xFF)
                if (command == SonyCommand.CONNECT_RET_CAPABILITY_INFO) {
                    Log.d("HyperPodsConnect-Driver", "FRAME $event [redacted]")
                } else {
                    Log.d("HyperPodsConnect-Driver", "RX ${TandemCodec.encode(event.frame).toLogHex()}")
                }
            }
            is SonySessionEvent.DecodeRejected ->
                Log.w("HyperPodsConnect-Driver", "REJECT ${event.reason}")
            is SonySessionEvent.AutoPlayTx ->
                Log.d("HyperPodsConnect-Driver", "AUTO_PLAY TX ${event.bytes.toLogHex()}")
            is SonySessionEvent.AutoPlayRx ->
                Log.d("HyperPodsConnect-Driver", "AUTO_PLAY RX ${event.bytes.toLogHex()}")
            is SonySessionEvent.AutoPlayUnavailable ->
                Log.w("HyperPodsConnect-Driver", "AUTO_PLAY unavailable: ${event.detail}")
        }
    }

    private fun ByteArray.toLogHex(maxBytes: Int = 128): String {
        val shown = take(maxBytes).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (size > maxBytes) "$shown …(+${size - maxBytes} bytes)" else shown
    }
}
