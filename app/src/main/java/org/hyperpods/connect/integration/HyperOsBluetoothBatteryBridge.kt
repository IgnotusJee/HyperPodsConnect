package org.hyperpods.connect.integration

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.lang.reflect.Method
import org.hyperpods.connect.hook.Log

/** Exact HyperOS 3 bridge to Android Bluetooth's public AdapterService battery API. */
object HyperOsBluetoothBatteryBridge {
    private const val TAG = "HyperPodsConnect-Battery"
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"

    private data class Contract(
        val getAdapterService: Method,
        val setBatteryLevel: Method,
    )

    @Volatile
    private var contract: Contract? = null
    @Volatile
    private var disabled = false
    @Volatile
    private var reported = false

    fun setBatteryLevel(
        context: Context,
        device: BluetoothDevice,
        level: Int,
    ): Boolean {
        if (disabled) return false
        val resolved = contract ?: synchronized(this) {
            contract ?: resolve(context).also { contract = it }
        } ?: return false
        return runCatching {
            val service = resolved.getAdapterService.invoke(null) ?: error("AdapterService unavailable")
            resolved.setBatteryLevel.invoke(service, device, level, false)
            true
        }.getOrElse { error ->
            disable("invoke ${error.javaClass.simpleName}:${error.message}")
            false
        }
    }

    private fun resolve(context: Context): Contract? = runCatching {
        val adapterService = Class.forName(ADAPTER_SERVICE, false, context.classLoader)
        Contract(
            getAdapterService = adapterService.getDeclaredMethod("getAdapterService"),
            setBatteryLevel = adapterService.getDeclaredMethod(
                "setBatteryLevel",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ),
        ).also { report("ACTIVE", null) }
    }.getOrElse { error ->
        disable("resolve ${error.javaClass.simpleName}:${error.message}")
        null
    }

    private fun disable(detail: String) {
        disabled = true
        report("DISABLED", detail)
    }

    private fun report(status: String, detail: String?) {
        if (reported) return
        synchronized(this) {
            if (reported) return
            reported = true
            Log.i(
                TAG,
                buildString {
                    append("HYPEROS_CONTRACT group=bluetooth-battery status=$status")
                    if (!detail.isNullOrBlank()) append(" detail=$detail")
                },
            )
        }
    }
}
