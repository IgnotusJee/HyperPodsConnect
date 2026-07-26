package moe.chenxy.headphones.core.device

/**
 * Application-stable key for a headset.
 *
 * Deliberately not the Bluetooth address: TWS earbuds rotate addresses, a set
 * can present a different member as the connected endpoint, and LE Audio groups
 * span several addresses. The address is one piece of evidence for resolving a
 * [DeviceId], never the identity itself.
 */
@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }

    companion object {
        /**
         * Derives an id from a Bluetooth address for devices that have no better
         * identifier yet. Normalises so that formatting differences between the
         * platform APIs cannot produce two ids for one device.
         */
        fun fromAddress(address: String): DeviceId {
            val normalized = address.filter { it.isLetterOrDigit() }.uppercase()
            require(normalized.isNotEmpty()) { "address has no usable characters: $address" }
            return DeviceId("bt:$normalized")
        }
    }
}

@JvmInline
value class VendorId(val value: String) {
    init {
        require(value.isNotBlank()) { "VendorId must not be blank" }
    }

    companion object {
        val OPPO = VendorId("oppo")
        val SONY = VendorId("sony")
    }
}

/**
 * Which addresses currently belong to one logical device.
 *
 * [primaryAddress] is whatever the platform is talking to right now; it can
 * change over the life of a session without the device changing.
 */
data class DeviceIdentity(
    val id: DeviceId,
    val vendorId: VendorId?,
    val primaryAddress: String,
    val memberAddresses: Set<String> = emptySet(),
    val groupId: String? = null,
) {
    val allAddresses: Set<String> get() = memberAddresses + primaryAddress
}

enum class TransportKind { CLASSIC_SPP, BLE_GATT }

/**
 * How confident we are about what a device is.
 *
 * The ordering matters: a name match must never unlock the same behaviour as a
 * completed protocol handshake, so callers compare rather than test equality.
 */
enum class DetectionConfidence {
    /** Name, Bluetooth class or a remembered profile. Must not trigger commands. */
    HINT,

    /** SDP UUID or GATT service. Enough to pick a driver and a transport. */
    TRANSPORT_EVIDENCE,

    /** A safe read-only handshake answered as expected. */
    PROTOCOL_EVIDENCE,
}

data class DeviceCandidate(
    val identity: DeviceIdentity,
    val displayName: String?,
    val bonded: Boolean,
    val advertisedUuids: Set<String> = emptySet(),
    val availableTransports: Set<TransportKind> = emptySet(),
)

data class DetectionEvidence(
    val vendorId: VendorId,
    val confidence: DetectionConfidence,
    val reasons: List<String>,
    val preferredTransports: List<TransportKind> = emptyList(),
)
