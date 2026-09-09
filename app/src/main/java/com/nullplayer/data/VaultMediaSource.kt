package com.nullplayer.data

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher

/**
 * A vault file offered to [android.media.MediaMetadataRetriever] as though it were plaintext.
 *
 * Tag readers do not stream, they seek: to the front for an ID3v2 header, to the very end for an
 * ID3v1 one, back and forth across the box tree of an MP4. So a file that arrived over a socket
 * cannot have its tags read from the stream that delivered it — by the time the end is in hand the
 * beginning is long gone. Reading them back out of the vault copy is the way round that, and
 * AES-CTR is what makes it cheap: the keystream for any byte offset can be produced without
 * touching the bytes before it, so a seek costs one cipher rather than a decryption of everything
 * up to that point.
 */
internal class VaultMediaSource(file: File) : MediaDataSource() {

    private val handle = RandomAccessFile(file, "r")
    private val iv = ByteArray(VaultCrypto.IV_LENGTH).also { handle.readFully(it) }
    private val length = (handle.length() - VaultCrypto.IV_LENGTH).coerceAtLeast(0)

    /**
     * The cipher the last read finished with, and the offset it stopped at.
     *
     * A read that carries on where the last one stopped keeps the cipher it already has, since CTR
     * has advanced the counter to exactly the right place by itself. That saves a key schedule and
     * an allocation per buffer rather than anything dramatic — but a tag reader walking forward
     * through a header is nearly all such reads, so it is close to free to keep.
     */
    private var cipher: Cipher? = null
    private var cipherAt = -1L

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val wanted = minOf(size.toLong(), length - position).toInt()
        if (wanted <= 0) return 0

        handle.seek(VaultCrypto.IV_LENGTH + position)
        val encrypted = ByteArray(wanted)
        var filled = 0
        while (filled < wanted) {
            val read = handle.read(encrypted, filled, wanted - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled <= 0) return -1

        val positioned = cipher?.takeIf { cipherAt == position }
            ?: VaultCrypto.decryptorAt(iv, position)
        positioned.update(encrypted, 0, filled, buffer, offset)
        cipher = positioned
        cipherAt = position + filled
        return filled
    }

    override fun close() {
        cipher = null
        cipherAt = -1L
        handle.close()
    }
}
