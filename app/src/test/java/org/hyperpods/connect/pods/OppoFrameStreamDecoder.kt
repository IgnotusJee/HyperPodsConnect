package org.hyperpods.connect.pods

/**
 * Compatibility name for the decoder now owned by `:protocol:oppo`.
 *
 * Existing app tests and parsers keep their package-local reference while the
 * production RFCOMM path and the protocol module use one implementation.
 */
typealias OppoFrameStreamDecoder =
    moe.chenxy.headphones.protocol.oppo.frame.OppoFrameStreamDecoder
