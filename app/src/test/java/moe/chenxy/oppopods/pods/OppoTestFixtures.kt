package moe.chenxy.oppopods.pods

internal object OppoTestFixtures {
    private const val ROOT = "/fixtures/oppo/official-source"

    fun officialSource(name: String): ByteArray {
        val text = checkNotNull(javaClass.getResource("$ROOT/$name")) {
            "Missing OPPO fixture: $name"
        }.readText()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .map { it.toInt(16).toByte() }
            .toList()
            .toByteArray()
    }

    fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

