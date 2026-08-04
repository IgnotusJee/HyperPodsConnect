package moe.chenxy.oppopods.runtime.bluetoothprocess

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.chenxy.headphones.core.transport.TransportSpec
import moe.chenxy.headphones.transport.android.AndroidGattDeviceResolver

/** Resolves Sony's rotating Auto Play BLE address from its manufacturer advertisement. */
internal class SonyAutoPlayGattDeviceResolver(
    context: Context,
    private val discoveryTimeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
) : AndroidGattDeviceResolver {
    private val scanner = context.applicationContext
        .getSystemService(BluetoothManager::class.java)
        .adapter
        .bluetoothLeScanner

    @SuppressLint("MissingPermission")
    override suspend fun resolve(
        connectedDevices: List<BluetoothDevice>,
        spec: TransportSpec.Gatt,
    ): BluetoothDevice? {
        val fallback = connectedDevices.firstOrNull() ?: return null
        if (!spec.serviceUuid.equals(AUTO_PLAY_SERVICE_UUID, ignoreCase = true)) return fallback
        val activeScanner = scanner ?: return fallback

        val identityHashes = connectedDevices
            .map { sonyAutoPlayUniqueId(it.address) }
            .toSet()
        val discovered = withTimeoutOrNull(discoveryTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val data = result.scanRecord
                            ?.getManufacturerSpecificData(SONY_COMPANY_ID)
                            ?: return
                        if (!isSonyAutoPlayAdvertisement(data, identityHashes)) return
                        val connectable = data[AUTO_PLAY_CONNECTABLE_OFFSET].toInt()
                        if (connectable != CONNECTABLE) {
                            Log.d(TAG, "Sony Auto Play endpoint advertised but unavailable: $connectable")
                            return
                        }
                        if (!continuation.isActive) return
                        runCatching { activeScanner.stopScan(this) }
                        continuation.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { activeScanner.stopScan(callback) }
                }
                try {
                    activeScanner.startScan(
                        emptyList(),
                        ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .build(),
                        callback,
                    )
                } catch (_: SecurityException) {
                    continuation.resume(null)
                } catch (_: IllegalStateException) {
                    continuation.resume(null)
                }
            }
        }
        if (discovered == null) {
            Log.d(TAG, "Sony Auto Play advertisement not connectable; using bonded endpoint")
        } else {
            Log.d(TAG, "Sony Auto Play advertisement resolved")
        }
        return discovered ?: fallback
    }

    private companion object {
        const val TAG = "OppoPods-Sony"
        const val AUTO_PLAY_SERVICE_UUID = "F76ACB00-7CAB-495F-BB1A-E664598FD77F"
        const val SONY_COMPANY_ID = 0x012D
        const val AUTO_PLAY_CONNECTABLE_OFFSET = 12
        const val CONNECTABLE = 0
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 3_000L
    }
}

internal fun sonyAutoPlayUniqueId(address: String): Int {
    val digest = MessageDigest.getInstance("SHA-1").digest(
        address.uppercase(Locale.ROOT).toByteArray(Charsets.UTF_8),
    )
    return ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
}

internal fun isSonyAutoPlayAdvertisement(data: ByteArray, identityHashes: Set<Int>): Boolean {
    if (data.size != 17 || data[0] != 0x13.toByte() || data[1] != 0x00.toByte()) return false
    val advertisedId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
    return advertisedId in identityHashes
}
