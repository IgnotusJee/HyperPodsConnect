package moe.chenxy.headphones.protocol.oppo

internal object OppoFixtures {
    private const val DEVICE_CAPTURE = "/fixtures/oppo/device-capture"
    private const val OFFICIAL_SOURCE = "/fixtures/oppo/official-source"

    fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    /** Real-device captures hold one frame per non-empty line. */
    fun deviceCaptureFrames(name: String): List<ByteArray> =
        lines("$DEVICE_CAPTURE/$name").map { it.toByteArray() }

    fun officialSource(name: String): ByteArray =
        lines("$OFFICIAL_SOURCE/$name").flatten().toByteArray()

    private fun lines(path: String): List<List<Byte>> {
        val text = checkNotNull(javaClass.getResource(path)) { "missing fixture: $path" }.readText()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                line.split(Regex("\\s+")).filter(String::isNotEmpty).map { it.toInt(16).toByte() }
            }
            .toList()
    }
}
