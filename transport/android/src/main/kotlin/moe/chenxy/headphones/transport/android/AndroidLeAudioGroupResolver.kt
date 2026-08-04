package moe.chenxy.headphones.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves a bonded LE Audio member to the currently connected group lead.
 *
 * A TWS member address can disappear when that earbud enters its case while
 * another member of the coordinated set remains connected. GATT transports
 * should target the active group lead for each new generation instead of
 * retaining the picker member forever.
 */
internal class AndroidLeAudioGroupResolver(
    private val context: Context,
    private val timeoutMillis: Long = DEFAULT_PROXY_TIMEOUT_MS,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    @SuppressLint("MissingPermission")
    /** Returns the lead and every connected member so vendor resolvers can match rotating endpoints. */
    suspend fun resolveConnectedGroupMembers(selected: BluetoothDevice): List<BluetoothDevice> {
        val proxy = awaitProxy() as? BluetoothLeAudio ?: return listOf(selected)
        return try {
            val groupId = proxy.getGroupId(selected)
            if (groupId == BluetoothLeAudio.GROUP_ID_INVALID) {
                listOf(selected)
            } else {
                buildList {
                    proxy.getConnectedGroupLeadDevice(groupId)?.let(::add)
                    addAll(proxy.connectedDevices.filter { member ->
                        proxy.getGroupId(member) == groupId
                    })
                    add(selected)
                }.distinctBy(BluetoothDevice::getAddress)
            }
        } catch (_: SecurityException) {
            listOf(selected)
        } finally {
            adapter.closeProfileProxy(BluetoothProfile.LE_AUDIO, proxy)
        }
    }

    private suspend fun awaitProxy(): BluetoothProfile? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.LE_AUDIO) return
                    if (continuation.isActive) {
                        continuation.resume(proxy)
                    } else {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.LE_AUDIO)) {
                continuation.resume(null)
            }
        }
    }

    private companion object {
        const val DEFAULT_PROXY_TIMEOUT_MS = 2_000L
    }
}
