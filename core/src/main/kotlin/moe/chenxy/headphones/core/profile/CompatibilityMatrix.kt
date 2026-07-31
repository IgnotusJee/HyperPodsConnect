package moe.chenxy.headphones.core.profile

import moe.chenxy.headphones.core.device.TransportKind
import moe.chenxy.headphones.core.device.VendorId
import moe.chenxy.headphones.core.feature.CompatibilityLevel

/** One exact, evidence-backed row in the device/firmware/transport matrix. */
data class CompatibilityMatrixEntry(
    val vendorId: VendorId,
    val model: String,
    val firmware: String,
    val transport: TransportKind,
    val level: CompatibilityLevel,
    val evidence: String,
) {
    init {
        require(level != CompatibilityLevel.DETECTED) { "Matrix rows require protocol evidence" }
        require(model.isNotBlank())
        require(firmware.isNotBlank())
        require(evidence.isNotBlank())
    }

    fun matches(
        vendorId: VendorId,
        model: String?,
        firmware: String?,
        transport: TransportKind,
    ): Boolean =
        this.vendorId == vendorId &&
            this.model.equals(model?.trim(), ignoreCase = true) &&
            this.firmware == firmware?.trim() &&
            this.transport == transport
}

class CompatibilityMatrix(entries: List<CompatibilityMatrixEntry>) {
    val entries: List<CompatibilityMatrixEntry> = entries.toList()

    init {
        require(this.entries.distinctBy {
            listOf(it.vendorId.value, it.model.lowercase(), it.firmware, it.transport.name)
        }.size == this.entries.size) { "Compatibility matrix contains duplicate rows" }
    }

    fun resolve(
        vendorId: VendorId,
        model: String?,
        firmware: String?,
        transport: TransportKind,
    ): CompatibilityMatrixEntry? = entries.firstOrNull {
        it.matches(vendorId, model, firmware, transport)
    }
}
