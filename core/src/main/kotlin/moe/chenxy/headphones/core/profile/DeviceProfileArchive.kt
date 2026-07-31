package moe.chenxy.headphones.core.profile

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Portable, vendor-neutral compatibility archive. */
@Serializable
data class DeviceProfileArchive(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMillis: Long,
    val profiles: List<ArchivedDeviceProfile>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "DeviceProfileArchive must use current schema $CURRENT_SCHEMA_VERSION"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ArchivedDeviceProfile(
    val deviceId: String,
    val vendorId: String,
    val model: String? = null,
    val firmware: String? = null,
    val primaryAddress: String,
    val historicalAddresses: Set<String> = emptySet(),
    val groupId: String? = null,
    val topology: String,
    val transport: String,
    val protocolName: String,
    val protocolVersion: String? = null,
    val commandTable: String? = null,
    val features: List<ArchivedFeatureCapability> = emptyList(),
    val compatibilityLevel: String,
    val compatibilityFingerprint: String,
    val lastConnectedAtEpochMillis: Long,
    /** Presentation resources are opaque to :core and interpreted by the app. */
    val uiResources: Map<String, String> = emptyMap(),
    /** Vendor/user overrides remain strings so new drivers can add keys compatibly. */
    val userOverrides: Map<String, String> = emptyMap(),
) {
    fun toDeviceProfile(): DeviceProfile {
        val vendor = VendorId(vendorId)
        val identity = DeviceIdentity(
            id = DeviceId(deviceId),
            vendorId = vendor,
            primaryAddress = primaryAddress,
            memberAddresses = historicalAddresses - primaryAddress,
            groupId = groupId,
        )
        val featureMap = features.associate { archived ->
            val featureId = FeatureId.valueOf(archived.featureId)
            featureId to FeatureCapability(
                featureId = featureId,
                canRead = archived.canRead,
                canWrite = archived.canWrite,
                evidence = EvidenceLevel.valueOf(archived.evidence),
                availableOnTransports = archived.availableOnTransports
                    .mapTo(linkedSetOf(), TransportKind::valueOf),
                requiresReadback = archived.requiresReadback,
                allowedValues = archived.allowedValues,
                valueLabels = archived.valueLabels,
                source = archived.source,
            )
        }
        val profile = DeviceProfile(
            identity = identity,
            vendorId = vendor,
            model = model,
            firmware = firmware,
            topology = DeviceTopology.valueOf(topology),
            transport = TransportKind.valueOf(transport),
            protocol = ProtocolDescriptor(protocolName, protocolVersion, commandTable),
            features = featureMap,
            compatibilityLevel = CompatibilityLevel.valueOf(compatibilityLevel),
        )
        require(profile.compatibilityFingerprint() == compatibilityFingerprint) {
            "Compatibility fingerprint does not match archive profile $deviceId"
        }
        return profile
    }

    companion object {
        fun from(
            profile: DeviceProfile,
            lastConnectedAtEpochMillis: Long,
            historicalAddresses: Set<String> = profile.identity.allAddresses,
            uiResources: Map<String, String> = emptyMap(),
            userOverrides: Map<String, String> = emptyMap(),
        ): ArchivedDeviceProfile = ArchivedDeviceProfile(
            deviceId = profile.identity.id.value,
            vendorId = profile.vendorId.value,
            model = profile.model,
            firmware = profile.firmware,
            primaryAddress = profile.identity.primaryAddress,
            historicalAddresses = historicalAddresses + profile.identity.allAddresses,
            groupId = profile.identity.groupId,
            topology = profile.topology.name,
            transport = profile.transport.name,
            protocolName = profile.protocol.name,
            protocolVersion = profile.protocol.version,
            commandTable = profile.protocol.commandTable,
            features = profile.features.values.sortedBy { it.featureId.name }.map {
                ArchivedFeatureCapability.from(it)
            },
            compatibilityLevel = profile.compatibilityLevel.name,
            compatibilityFingerprint = profile.compatibilityFingerprint(),
            lastConnectedAtEpochMillis = lastConnectedAtEpochMillis,
            uiResources = uiResources.toSortedMap(),
            userOverrides = userOverrides.toSortedMap(),
        )
    }
}

@Serializable
data class ArchivedFeatureCapability(
    val featureId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val evidence: String,
    val availableOnTransports: Set<String>,
    val requiresReadback: Boolean,
    val allowedValues: Set<String> = emptySet(),
    val valueLabels: Map<String, String> = emptyMap(),
    val source: String? = null,
) {
    companion object {
        fun from(capability: FeatureCapability) = ArchivedFeatureCapability(
            featureId = capability.featureId.name,
            canRead = capability.canRead,
            canWrite = capability.canWrite,
            evidence = capability.evidence.name,
            availableOnTransports = capability.availableOnTransports.mapTo(linkedSetOf()) { it.name },
            requiresReadback = capability.requiresReadback,
            allowedValues = capability.allowedValues,
            valueLabels = capability.valueLabels.toSortedMap(),
            source = capability.source,
        )
    }
}

data class ProfileArchiveImport(
    val archive: DeviceProfileArchive,
    val migratedFromSchemaVersion: Int? = null,
)

object DeviceProfileArchiveCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(archive: DeviceProfileArchive): String =
        json.encodeToString(DeviceProfileArchive.serializer(), archive)

    fun decode(value: String): ProfileArchiveImport {
        val root = runCatching { json.parseToJsonElement(value).jsonObject }
            .getOrElse { throw SerializationException("Invalid profile archive JSON", it) }
        val sourceVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
        require(sourceVersion <= DeviceProfileArchive.CURRENT_SCHEMA_VERSION) {
            "Profile archive schema $sourceVersion is newer than supported schema " +
                DeviceProfileArchive.CURRENT_SCHEMA_VERSION
        }
        val decoded = json.decodeFromString(LegacyCompatibleArchive.serializer(), value)
        val profiles = decoded.profiles.map { profile ->
            if (profile.compatibilityFingerprint.isBlank()) {
                val converted = profile.toUncheckedDeviceProfile()
                profile.copy(compatibilityFingerprint = converted.compatibilityFingerprint())
            } else {
                profile
            }
        }
        return ProfileArchiveImport(
            archive = DeviceProfileArchive(
                exportedAtEpochMillis = decoded.exportedAtEpochMillis,
                profiles = profiles.distinctBy { it.deviceId },
            ),
            migratedFromSchemaVersion = sourceVersion.takeIf {
                it != DeviceProfileArchive.CURRENT_SCHEMA_VERSION
            },
        )
    }

    private fun ArchivedDeviceProfile.toUncheckedDeviceProfile(): DeviceProfile {
        val repaired = copy(compatibilityFingerprint = "pending")
        val vendor = VendorId(repaired.vendorId)
        return DeviceProfile(
            identity = DeviceIdentity(
                DeviceId(repaired.deviceId),
                vendor,
                repaired.primaryAddress,
                repaired.historicalAddresses - repaired.primaryAddress,
                repaired.groupId,
            ),
            vendorId = vendor,
            model = repaired.model,
            firmware = repaired.firmware,
            topology = DeviceTopology.valueOf(repaired.topology),
            transport = TransportKind.valueOf(repaired.transport),
            protocol = ProtocolDescriptor(
                repaired.protocolName,
                repaired.protocolVersion,
                repaired.commandTable,
            ),
            features = repaired.features.associate { archived ->
                val id = FeatureId.valueOf(archived.featureId)
                id to FeatureCapability(
                    id,
                    archived.canRead,
                    archived.canWrite,
                    EvidenceLevel.valueOf(archived.evidence),
                    archived.availableOnTransports.mapTo(linkedSetOf(), TransportKind::valueOf),
                    archived.requiresReadback,
                    archived.allowedValues,
                    archived.valueLabels,
                    archived.source,
                )
            },
            compatibilityLevel = CompatibilityLevel.valueOf(repaired.compatibilityLevel),
        )
    }

    @Serializable
    private data class LegacyCompatibleArchive(
        val exportedAtEpochMillis: Long = 0,
        val profiles: List<ArchivedDeviceProfile> = emptyList(),
    )
}

/** Stable fingerprint for capability/profile comparisons and archive validation. */
fun DeviceProfile.compatibilityFingerprint(): String {
    val canonical = buildString {
        append(vendorId.value).append('|')
        append(model.orEmpty()).append('|')
        append(firmware.orEmpty()).append('|')
        append(transport.name).append('|')
        append(protocol.name).append('|')
        append(protocol.version.orEmpty()).append('|')
        append(protocol.commandTable.orEmpty()).append('|')
        append(compatibilityLevel.name)
        features.toSortedMap(compareBy(FeatureId::name)).forEach { (id, capability) ->
            append('|').append(id.name)
            append(':').append(capability.canRead)
            append(':').append(capability.canWrite)
            append(':').append(capability.evidence.name)
            append(':').append(capability.availableOnTransports.map { it.name }.sorted().joinToString(","))
            append(':').append(capability.requiresReadback)
            append(':').append(capability.allowedValues.sorted().joinToString(","))
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
