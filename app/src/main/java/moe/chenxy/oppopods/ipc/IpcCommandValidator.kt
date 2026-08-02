package moe.chenxy.oppopods.ipc

internal object IpcCommandValidator {
    fun targetsCurrentSession(
        envelope: IpcCommandEnvelope,
        currentDeviceId: String?,
        currentVendorId: String?,
    ): Boolean =
        !envelope.deviceId.isNullOrBlank() &&
            !envelope.vendorId.isNullOrBlank() &&
            currentDeviceId != null &&
            currentVendorId != null &&
            envelope.deviceId == currentDeviceId &&
            envelope.vendorId == currentVendorId
}
