package moe.chenxy.headphones.protocol.oppo.session

import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneDriverProvider
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.protocol.oppo.compatibility.OppoCompatibilityOverrides

class OppoDriverProvider(
    private val overrides: OppoCompatibilityOverrides = OppoCompatibilityOverrides(),
) : HeadphoneDriverProvider {
    override val vendorId: VendorId = VendorId.OPPO

    override fun inspect(candidate: DeviceCandidate): DetectionEvidence? {
        val uuidMatch = candidate.advertisedUuids.any {
            it.equals(OppoSession.OPPO_SPP_UUID, ignoreCase = true)
        }
        val nameMatch = candidate.displayName.orEmpty().contains("oppo", ignoreCase = true) ||
            candidate.displayName.orEmpty().contains("oneplus", ignoreCase = true)
        if (!uuidMatch && !nameMatch) return null
        return DetectionEvidence(
            vendorId = vendorId,
            confidence = if (uuidMatch) {
                DetectionConfidence.TRANSPORT_EVIDENCE
            } else {
                DetectionConfidence.HINT
            },
            reasons = buildList {
                if (uuidMatch) add("OPPO SPP UUID")
                if (nameMatch) add("OPPO-family model name")
            },
            preferredTransports = listOf(TransportKind.CLASSIC_SPP),
        )
    }

    override suspend fun createSession(context: DriverSessionContext): HeadphoneSession =
        OppoSession(context, overrides)
}
