package moe.chenxy.headphones.protocol.sony.session

import moe.chenxy.headphones.core.device.DetectionConfidence
import moe.chenxy.headphones.core.device.DetectionEvidence
import moe.chenxy.headphones.core.device.DeviceCandidate
import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.driver.DriverSessionContext
import moe.chenxy.headphones.core.driver.HeadphoneDriverProvider
import moe.chenxy.headphones.core.driver.HeadphoneSession
import moe.chenxy.headphones.protocol.sony.profile.SonyProfile

class SonyDriverProvider : HeadphoneDriverProvider {
    override val vendorId: VendorId = VendorId.SONY

    override fun inspect(candidate: DeviceCandidate): DetectionEvidence? {
        val uuid = SonySession.resolveServiceUuid(candidate)
        val name = candidate.displayName.orEmpty()
        val nameHint = name.startsWith("WH-", ignoreCase = true) ||
            name.startsWith("WF-", ignoreCase = true) ||
            name.startsWith("WI-", ignoreCase = true) ||
            name.contains("LinkBuds", ignoreCase = true)
        if (uuid == null && !nameHint) return null
        return DetectionEvidence(
            vendorId = vendorId,
            confidence = if (uuid != null) {
                DetectionConfidence.TRANSPORT_EVIDENCE
            } else {
                DetectionConfidence.HINT
            },
            reasons = buildList {
                uuid?.let { add("advertised Sony SPP UUID $it") }
                if (nameHint) add("Sony-family model-name hint")
            },
            preferredTransports = listOf(TransportKind.CLASSIC_SPP),
        )
    }

    override suspend fun createSession(context: DriverSessionContext): HeadphoneSession =
        SonySession(context)
}
