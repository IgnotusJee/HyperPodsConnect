package moe.chenxy.headphones.protocol.sony

import moe.chenxy.headphones.protocol.sony.frame.TandemCodec
import moe.chenxy.headphones.protocol.sony.frame.TandemDecodeResult
import moe.chenxy.headphones.protocol.sony.frame.TandemFrame
import moe.chenxy.headphones.protocol.sony.frame.TandemStreamDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TandemCodecTest {
    @Test
    fun `encodes protocol-info query with unsigned checksum`() {
        val encoded = TandemCodec.encode(TandemFrame(0x0C, 0, byteArrayOf(0, 0)))

        assertArrayEquals(
            "3E0C000000000200000E3C".hex(),
            encoded,
        )
    }

    @Test
    fun `escapes every reserved byte in body`() {
        val encoded = TandemCodec.encode(
            TandemFrame(0x0C, 1, byteArrayOf(0x3C, 0x3D, 0x3E)),
        )

        assertArrayEquals(
            "3E0C01000000033D2C3D2D3D2EC73C".hex(),
            encoded,
        )
    }

    @Test
    fun `stream decoder survives noise fragmentation and concatenation`() {
        val first = TandemCodec.encode(TandemFrame(0x0C, 0, byteArrayOf(0, 0)))
        val second = TandemCodec.encode(TandemFrame(0x01, 1, byteArrayOf()))
        val decoder = TandemStreamDecoder()

        assertTrue(decoder.feed(byteArrayOf(1, 2, 3) + first.copyOfRange(0, 4)).isEmpty())
        val results = decoder.feed(first.copyOfRange(4, first.size) + second)
            .filterIsInstance<TandemDecodeResult.Frame>()

        assertEquals(2, results.size)
        assertEquals(TandemFrame(0x0C, 0, byteArrayOf(0, 0)), results[0].value)
        assertEquals(TandemFrame(0x01, 1, byteArrayOf()), results[1].value)
    }

    @Test
    fun `rejects bad checksum without producing a frame`() {
        val corrupted = "3E0C000000000200000F3C".hex()
        val result = TandemStreamDecoder().feed(corrupted).single()

        assertTrue(result is TandemDecodeResult.Rejected)
    }

    @Test
    fun `official static fixture contains only valid frames`() {
        val lines = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/official-static/phase8-readonly.hex",
            ),
        ).bufferedReader().readLines()
        val frames = lines.filterNot { it.isBlank() || it.startsWith("#") }

        assertEquals(4, frames.size)
        frames.forEach { line ->
            val result = TandemStreamDecoder().feed(line.hex()).single()
            assertTrue("$line -> $result", result is TandemDecodeResult.Frame)
        }
    }

    @Test
    fun `sanitized WH-1000XM4 capture contains valid frames and no capability response`() {
        val lines = requireNotNull(
            javaClass.getResourceAsStream(
                "/fixtures/sony/device-capture/wh-1000xm4-2.5.1/wh-1000xm4-readonly.hex",
            ),
        ).bufferedReader().readLines()
        val frames = lines.filterNot { it.isBlank() || it.startsWith("#") }
            .map { line ->
                val result = TandemStreamDecoder().feed(line.hex()).single()
                assertTrue("$line -> $result", result is TandemDecodeResult.Frame)
                (result as TandemDecodeResult.Frame).value
            }

        assertEquals(10, frames.size)
        assertTrue(
            frames.none { frame ->
                (frame.payload.firstOrNull()?.toInt()?.and(0xFF)) == 0x03
            },
        )
    }
}

private fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
