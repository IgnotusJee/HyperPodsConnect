package moe.chenxy.headphones.engine

import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.HeadphoneDriverProvider

data class DriverMatch(
    val provider: HeadphoneDriverProvider,
    val evidence: DetectionEvidence,
)

/**
 * Vendor driver catalogue used by the runtime engine.
 *
 * Driver inspection is deliberately side-effect free. A name hint can rank a
 * driver, but only transport/protocol evidence may cause an automatic control
 * session to be opened.
 */
class DriverRegistry(
    providers: Iterable<HeadphoneDriverProvider> = emptyList(),
) {
    private val providersByVendor = linkedMapOf<VendorId, HeadphoneDriverProvider>()

    init {
        providers.forEach(::register)
    }

    @Synchronized
    fun register(provider: HeadphoneDriverProvider) {
        providersByVendor[provider.vendorId] = provider
    }

    @Synchronized
    fun unregister(vendorId: VendorId) {
        providersByVendor.remove(vendorId)
    }

    @Synchronized
    fun resolve(candidate: DeviceCandidate): DriverMatch? =
        providersByVendor.values
            .mapNotNull { provider ->
                provider.inspect(candidate)?.let { DriverMatch(provider, it) }
            }
            .maxWithOrNull(
                compareBy<DriverMatch> { it.evidence.confidence.ordinal }
                    .thenBy { candidate.identity.vendorId == it.provider.vendorId },
            )

    @Synchronized
    fun provider(vendorId: VendorId): HeadphoneDriverProvider? = providersByVendor[vendorId]

    @Synchronized
    fun vendors(): Set<VendorId> = providersByVendor.keys.toSet()

    companion object {
        fun canAutoConnect(match: DriverMatch): Boolean =
            match.evidence.confidence >= DetectionConfidence.TRANSPORT_EVIDENCE

        /**
         * A protected platform profile-connected event is stronger than a name hint alone: the
         * device is already bonded and connected by Android. The session must still complete its
         * read-only protocol handshake before any capability becomes writable.
         */
        fun canConnectFromProfile(match: DriverMatch, candidate: DeviceCandidate): Boolean =
            candidate.bonded &&
                candidate.identity.vendorId != null &&
                candidate.identity.vendorId == match.provider.vendorId
    }
}
