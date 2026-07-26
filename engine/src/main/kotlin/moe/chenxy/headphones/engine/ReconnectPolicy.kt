package moe.chenxy.headphones.engine

import moe.chenxy.headphones.core.session.DisconnectCause

data class ReconnectPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 1_000,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 30_000,
) {
    init {
        require(maxAttempts >= 0)
        require(initialDelayMillis >= 0)
        require(multiplier >= 1.0)
        require(maxDelayMillis >= initialDelayMillis)
    }

    fun delayMillis(attempt: Int, cause: DisconnectCause): Long? {
        if (!cause.isRetryable || attempt !in 1..maxAttempts) return null
        var delay = initialDelayMillis.toDouble()
        repeat(attempt - 1) { delay = (delay * multiplier).coerceAtMost(maxDelayMillis.toDouble()) }
        return delay.toLong().coerceAtMost(maxDelayMillis)
    }
}
