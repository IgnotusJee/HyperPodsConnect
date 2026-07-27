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
        val route = SonySession.resolveTransport(candidate)
        val nameHint = SonySession.isSonyNameHint(candidate.displayName)
        if (route == null && !nameHint) return null
        return DetectionEvidence(
            vendorId = vendorId,
            confidence = if (route?.detectionBasis == DetectionBasis.ADVERTISED_SERVICE) {
                DetectionConfidence.TRANSPORT_EVIDENCE
            } else {
                DetectionConfidence.HINT
            },
            reasons = buildList {
                route?.takeIf {
                    it.detectionBasis == DetectionBasis.ADVERTISED_SERVICE
                }?.let { add("advertised Sony ${it.kind} service") }
                route?.takeIf {
                    it.detectionBasis == DetectionBasis.BONDED_SERVICE_VALIDATION
                }?.let { add("bonded Sony candidate; GATT service validation required") }
                if (nameHint) add("Sony-family model-name hint")
            },
            preferredTransports = route?.let { listOf(it.kind) }.orEmpty(),
        )
    }

    override suspend fun createSession(context: DriverSessionContext): HeadphoneSession =
        SonySession(context)
}
