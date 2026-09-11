package com.nullplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The counter arithmetic AES-CTR seeking rests on.
 *
 * This is the least forgiving code in the vault. A cipher positioned one block out does not fail,
 * it produces noise -- and it produces it only for the seek that got it wrong, so the file still
 * plays from the start and the bug lives in the part nobody tests by ear. The carry is the whole
 * of it: sixteen bytes of big-endian integer, incremented by a block count that can be large.
 */
class VaultCounterTest {

    private fun iv(vararg tail: Int): ByteArray {
        val bytes = ByteArray(16)
        tail.forEachIndexed { index, value -> bytes[16 - tail.size + index] = value.toByte() }
        return bytes
    }

    @Test
    fun `adding nothing moves nothing`() {
        val start = iv(0x01, 0x02, 0x03)
        assertArrayEquals(start, VaultCrypto.counterPlus(start, 0))
    }

    @Test
    fun `one block lands on the next byte`() {
        assertArrayEquals(iv(1), VaultCrypto.counterPlus(iv(), 1))
        assertArrayEquals(iv(0x10), VaultCrypto.counterPlus(iv(0x0F), 1))
    }

    @Test
    fun `a carry crosses into the byte above`() {
        assertArrayEquals(iv(0x01, 0x00), VaultCrypto.counterPlus(iv(0x00, 0xFF), 1))
        assertArrayEquals(iv(0x01, 0x00, 0x00), VaultCrypto.counterPlus(iv(0x00, 0xFF, 0xFF), 1))
    }

    @Test
    fun `a block count larger than a byte is spread across bytes`() {
        assertArrayEquals(iv(0x01, 0x00), VaultCrypto.counterPlus(iv(), 256))
        assertArrayEquals(iv(0x01, 0x00, 0x00), VaultCrypto.counterPlus(iv(), 65_536))
        assertArrayEquals(iv(0x12, 0x34, 0x56), VaultCrypto.counterPlus(iv(), 0x123456))
    }

    @Test
    fun `a seek far into a large file carries correctly`() {
        // Four gigabytes in, which is 2^28 blocks: past anything a byte-at-a-time carry survives.
        val blocks = 1L shl 28
        assertArrayEquals(iv(0x10, 0x00, 0x00, 0x00), VaultCrypto.counterPlus(iv(), blocks))
    }

    @Test
    fun `the counter wraps the way AES-CTR does`() {
        val all = ByteArray(16) { 0xFF.toByte() }
        assertArrayEquals(ByteArray(16), VaultCrypto.counterPlus(all, 1))
    }

    @Test
    fun `an addition onto a random-looking iv is still an addition`() {
        val start = iv(0xAB, 0xCD, 0xEF, 0xFE)
        val moved = VaultCrypto.counterPlus(start, 0x02)
        assertArrayEquals(iv(0xAB, 0xCD, 0xF0, 0x00), moved)
    }

    @Test
    fun `the iv it was given is left alone`() {
        // The caller keeps the file's own IV and derives from it repeatedly; a counter that
        // modified it in place would corrupt every read after the first seek.
        val start = iv(0x00, 0xFF)
        val before = start.copyOf()
        VaultCrypto.counterPlus(start, 1)
        assertArrayEquals(before, start)
    }

    @Test
    fun `the counter is always sixteen bytes`() {
        assertEquals(16, VaultCrypto.counterPlus(iv(), 12_345).size)
    }
}
