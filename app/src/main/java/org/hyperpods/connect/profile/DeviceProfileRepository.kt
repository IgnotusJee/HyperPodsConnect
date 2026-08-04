package org.hyperpods.connect.profile

import android.content.SharedPreferences
import moe.chenxy.headphones.core.device.DeviceId
import moe.chenxy.headphones.core.device.DeviceIdentity
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel
import moe.chenxy.headphones.core.feature.DeviceProfile
import moe.chenxy.headphones.core.feature.DeviceTopology
import moe.chenxy.headphones.core.feature.EvidenceLevel
import moe.chenxy.headphones.core.feature.FeatureCapability
import moe.chenxy.headphones.core.feature.FeatureId
import moe.chenxy.headphones.core.feature.ProtocolDescriptor
import moe.chenxy.headphones.core.profile.ArchivedDeviceProfile
import moe.chenxy.headphones.core.profile.DeviceProfileArchive
import moe.chenxy.headphones.core.profile.DeviceProfileArchiveCodec
import moe.chenxy.headphones.core.profile.ProfileArchiveImport
import org.hyperpods.connect.ipc.HeadphoneSnapshotPayload

/**
 * Versioned profile persistence and compatibility archive import/export.
 */
class DeviceProfileRepository(private val prefs: SharedPreferences) {
    fun profiles(): List<ArchivedDeviceProfile> = readArchive().profiles

    fun upsert(profile: ArchivedDeviceProfile): List<ArchivedDeviceProfile> {
        profile.toDeviceProfile() // Validate enums and the compatibility fingerprint.
        val current = profiles()
        val previous = current.firstOrNull { it.deviceId == profile.deviceId }
        val merged = profile.copy(
            historicalAddresses = profile.historicalAddresses +
                previous?.historicalAddresses.orEmpty() +
                listOfNotNull(previous?.primaryAddress),
            uiResources = previous?.uiResources.orEmpty() + profile.uiResources,
            userOverrides = previous?.userOverrides.orEmpty() + profile.userOverrides,
            lastConnectedAtEpochMillis = maxOf(
                profile.lastConnectedAtEpochMillis,
                previous?.lastConnectedAtEpochMillis ?: 0,
            ),
        )
        return save(listOf(merged) + current.filterNot { it.deviceId == merged.deviceId })
    }

    fun acceptSnapshot(snapshot: HeadphoneSnapshotPayload): Boolean {
        val archived = snapshot.toArchivedDeviceProfile() ?: return false
        upsert(archived)
        return true
    }

    fun exportArchive(nowEpochMillis: Long = System.currentTimeMillis()): String =
        DeviceProfileArchiveCodec.encode(
            DeviceProfileArchive(
                exportedAtEpochMillis = nowEpochMillis,
                profiles = profiles().sortedBy { it.deviceId },
            ),
        )

    fun importArchive(value: String): ProfileArchiveImport {
        val imported = DeviceProfileArchiveCodec.decode(value)
        imported.archive.profiles.forEach { it.toDeviceProfile() }
        val byId = profiles().associateBy { it.deviceId }.toMutableMap()
        imported.archive.profiles.forEach { candidate ->
            val existing = byId[candidate.deviceId]
            byId[candidate.deviceId] = if (existing == null) {
                candidate
            } else {
                val newest = if (
                    candidate.lastConnectedAtEpochMillis >= existing.lastConnectedAtEpochMillis
                ) candidate else existing
                newest.copy(
                    historicalAddresses = existing.historicalAddresses +
                        candidate.historicalAddresses +
                        setOf(existing.primaryAddress, candidate.primaryAddress),
                    uiResources = existing.uiResources + candidate.uiResources,
                    userOverrides = existing.userOverrides + candidate.userOverrides,
                )
            }
        }
        save(byId.values.sortedByDescending { it.lastConnectedAtEpochMillis })
        return imported
    }

    private fun readArchive(): DeviceProfileArchive {
        val raw = prefs.getString(PREF_KEY_ARCHIVE, null)
            ?: return DeviceProfileArchive(
                exportedAtEpochMillis = 0,
                profiles = emptyList(),
            )
        return runCatching { DeviceProfileArchiveCodec.decode(raw).archive }
            .getOrElse {
                DeviceProfileArchive(
                    exportedAtEpochMillis = 0,
                    profiles = emptyList(),
                )
            }
    }

    private fun save(profiles: List<ArchivedDeviceProfile>): List<ArchivedDeviceProfile> {
        val normalized = profiles.distinctBy { it.deviceId }
        val archive = DeviceProfileArchive(
            exportedAtEpochMillis = System.currentTimeMillis(),
            profiles = normalized,
        )
        check(
            prefs.edit()
                .putString(PREF_KEY_ARCHIVE, DeviceProfileArchiveCodec.encode(archive))
                .commit(),
        ) { "Unable to persist device profiles" }
        return normalized
    }

    companion object {
        const val PREF_KEY_ARCHIVE = "device_profile_archive_v1"
    }
}

internal fun HeadphoneSnapshotPayload.toArchivedDeviceProfile(): ArchivedDeviceProfile? {
    val vendor = vendorId?.takeIf(String::isNotBlank)?.let(::VendorId) ?: return null
    val address = primaryAddress?.takeIf(String::isNotBlank) ?: return null
    val transportKind = transport?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() }
        ?: return null
    val topologyKind = topology?.let { runCatching { DeviceTopology.valueOf(it) }.getOrNull() }
        ?: return null
    val compatibilityLevel = compatibility?.let {
        runCatching { CompatibilityLevel.valueOf(it) }.getOrNull()
    } ?: return null
    val profile = DeviceProfile(
        identity = DeviceIdentity(
            DeviceId(deviceId),
            vendor,
            address,
            memberAddresses,
            groupId,
        ),
        vendorId = vendor,
        model = deviceName,
        firmware = firmware,
        topology = topologyKind,
        transport = transportKind,
        protocol = ProtocolDescriptor(
            protocolName ?: return null,
            protocolVersion,
            commandTable,
        ),
        features = capabilities.mapNotNull { payload ->
            val featureId = runCatching { FeatureId.valueOf(payload.featureId) }.getOrNull()
                ?: return@mapNotNull null
            val evidence = runCatching { EvidenceLevel.valueOf(payload.evidence) }.getOrNull()
                ?: return@mapNotNull null
            val transports = payload.availableOnTransports.mapNotNullTo(linkedSetOf()) {
                runCatching { TransportKind.valueOf(it) }.getOrNull()
            }.ifEmpty { setOf(transportKind) }
            featureId to FeatureCapability(
                featureId = featureId,
                canRead = payload.canRead,
                canWrite = payload.canWrite,
                evidence = evidence,
                availableOnTransports = transports,
                requiresReadback = payload.requiresReadback,
                allowedValues = payload.allowedValues.toSet(),
                valueLabels = payload.valueLabels,
                equalizerCurveSpec = payload.equalizerCurveSpec?.toDomain(),
                source = payload.source,
            )
        }.toMap(),
        compatibilityLevel = compatibilityLevel,
    )
    return ArchivedDeviceProfile.from(
        profile,
        lastConnectedAtEpochMillis = emittedAtMillis,
    )
}
