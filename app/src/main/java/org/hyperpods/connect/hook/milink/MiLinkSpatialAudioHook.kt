package org.hyperpods.connect.hook.milink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import org.hyperpods.connect.hook.Log
import org.hyperpods.connect.hook.callMethod
import org.hyperpods.connect.hook.getObjectField

/** HyperOS 3 MiLink exposes spatial audio as one binary audio-effect switch. */
internal class MiLinkSpatialAudioHook(private val hook: MiLinkServiceHook) {
    fun hookProfileSpatialAudio() {
        hookProfileAudioEffectGetter()
        hookProfileAudioEffectSetter()
        hook.hookHeadsetInfoNoArg("getAudioEffectState") { hook.miLinkAudioEffectState() }
        hook.hookHeadsetInfoNoArg("component10") { hook.miLinkAudioEffectState() }
    }

    fun hookCirculateHeadsetServiceInfo() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(
                    "com.miui.circulate.api.service.CirculateServiceInfo",
                    "setHeadsetId",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
            ) {
                val headsetId = args[0] as? String ?: return@hookAfter
                val address = getObjectField(instance, "deviceId") as? String ?: return@hookAfter
                if (!hook.isCurrentSupportedAddress(address) &&
                    address != hook.currentAddress &&
                    headsetId != hook.hyperOsPresentationTypeId()
                ) return@hookAfter
                if (hook.spatialAudioPanelEnabled()) return@hookAfter
                val serviceProperties = getObjectField(instance, "serviceProperties")
                val bundle = callMethod(serviceProperties, "getAll") as? Bundle ?: return@hookAfter
                bundle.putInt("headset_switch_state", 0)
            }
        }.onFailure {
            Log.w(MiLinkServiceHook.TAG, "hook CirculateServiceInfo.setHeadsetId skipped: ${it.message}")
        }
    }

    private fun hookProfileAudioEffectGetter() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(
                    "com.miui.headset.runtime.ProfileContext",
                    "getAudioSpatialEffectState",
                    BluetoothDevice::class.java,
                ),
            ) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!hook.isCurrentSupportedHeadset(device)) return@hookAfter
                hook.lastProfileContext = instance
                hook.captureRuntimeContext(instance)
                result = hook.miLinkAudioEffectState()
            }
        }.onFailure {
            Log.w(MiLinkServiceHook.TAG, "hook ProfileContext.getAudioSpatialEffectState skipped: ${it.message}")
        }
    }

    private fun hookProfileAudioEffectSetter() {
        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.ProfileContext",
                    "setAudioEffectState",
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
            ) {
                val address = args[0] as? String ?: return@hookBefore
                if (!hook.isCurrentSupportedAddress(address)) return@hookBefore
                val binaryState = args[1] as? Int ?: return@hookBefore
                val domainMode = hook.spatialAudioFromMiLink(binaryState) ?: return@hookBefore
                val device = runCatching {
                    BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
                }.getOrNull()
                hook.lastProfileContext = instance
                hook.captureRuntimeContext(instance)
                hook.updateSpatialAudioMode(domainMode)
                hook.sendHeadphoneSpatialAudio(domainMode)
                if (device != null) hook.notifySpatialUiChanged(instance, device, domainMode)
                result = null
            }
        }.onFailure {
            Log.w(MiLinkServiceHook.TAG, "hook ProfileContext.setAudioEffectState skipped: ${it.message}")
        }
    }
}
