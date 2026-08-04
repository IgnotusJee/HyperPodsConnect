package moe.chenxy.headphones.protocol.sony.feature.wearing

import moe.chenxy.headphones.core.feature.DeviceReport
import moe.chenxy.headphones.core.feature.WearComponent
import moe.chenxy.headphones.core.feature.WearState

/** Sony Auto Play BLE status channel used by LinkBuds-family devices. */
object SonyAutoPlayWearingFeature {
    const val FUNCTION_ID = 0xA2
    const val CONNECT_MESSAGE = 0x14
    const val HEADPHONE_STATUS_MESSAGE = 0x06

    val clientId: ByteArray
        // Client ID used by Sony's Auto Play core (C19430m).
        get() = byteArrayOf(0x00, 0x7C)

    fun connect(clientId: ByteArray = this.clientId): ByteArray =
        message(CONNECT_MESSAGE, clientId)

    fun query(clientId: ByteArray = this.clientId): ByteArray =
        message(HEADPHONE_STATUS_MESSAGE, clientId)

    fun matches(bytes: ByteArray, messageType: Int, clientId: ByteArray = this.clientId): Boolean =
        clientId.size == CLIENT_ID_SIZE &&
            bytes.size >= MIN_HEADER_SIZE &&
            (bytes[0].toInt() and 0xFF) == messageType &&
            bytes[1] == clientId[0] &&
            bytes[2] == clientId[1]

    fun parse(bytes: ByteArray, clientId: ByteArray = this.clientId): DeviceReport.Wearing? {
        if (
            bytes.size < MIN_STATUS_SIZE ||
            !matches(bytes, HEADPHONE_STATUS_MESSAGE, clientId)
        ) {
            return null
        }
        // Official Auto Play parser treats status byte 1 bit 0 as right wear
        // and bit 1 as left wear. Status byte 0 carries unrelated activity flags.
        val wearingBits = bytes[4].toInt() and 0xFF
        return DeviceReport.Wearing(
            mapOf(
                WearComponent.LEFT to wearingBits.wearState(LEFT_WEAR_BIT),
                WearComponent.RIGHT to wearingBits.wearState(RIGHT_WEAR_BIT),
            ),
        )
    }

    private fun message(type: Int, clientId: ByteArray): ByteArray {
        require(clientId.size == CLIENT_ID_SIZE) { "Sony Auto Play client ID must be 2 bytes" }
        require(!(clientId[0] == 0.toByte() && clientId[1] == 0.toByte())) {
            "Sony Auto Play client ID cannot be 0000"
        }
        require(!(clientId[0] == 0xFF.toByte() && clientId[1] == 0xFF.toByte())) {
            "Sony Auto Play client ID cannot be FFFF"
        }
        return byteArrayOf(type.toByte(), clientId[0], clientId[1])
    }

    private fun Int.wearState(bit: Int): WearState =
        if (this and (1 shl bit) != 0) WearState.WEARING else WearState.REMOVED

    private const val CLIENT_ID_SIZE = 2
    private const val MIN_HEADER_SIZE = 3
    private const val MIN_STATUS_SIZE = 5
    private const val RIGHT_WEAR_BIT = 0
    private const val LEFT_WEAR_BIT = 1
}
