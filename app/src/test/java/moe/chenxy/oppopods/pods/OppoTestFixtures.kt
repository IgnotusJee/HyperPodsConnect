package moe.chenxy.oppopods.pods

internal object OppoTestFixtures {
    private const val OFFICIAL_SOURCE_ROOT = "/fixtures/oppo/official-source"
    private const val DEVICE_CAPTURE_ROOT = "/fixtures/oppo/device-capture"

    fun officialSource(name: String): ByteArray =
        hexLines("$OFFICIAL_SOURCE_ROOT/$name").flatten().toByteArray()

    /**
     * Real-device captures hold several frames per file, one per non-empty line,
     * so they are returned as a list instead of a single concatenated blob.
     */
    fun deviceCaptureFrames(name: String): List<ByteArray> =
        hexLines("$DEVICE_CAPTURE_ROOT/$name").map { it.toByteArray() }

    private fun hexLines(path: String): List<List<Byte>> {
        val text = checkNotNull(javaClass.getResource(path)) {
            "Missing OPPO fixture: $path"
        }.readText()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                line.split(Regex("\\s+")).filter(String::isNotEmpty).map { it.toInt(16).toByte() }
            }
            .toList()
    }

    fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

