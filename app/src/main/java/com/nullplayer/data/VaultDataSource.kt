package com.nullplayer.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import kotlin.math.min

/**
 * Feeds ExoPlayer from the encrypted vault, decrypting on the fly.
 *
 * Layout of a vault file: a 16-byte IV, then the AES-CTR ciphertext. Because CTR is seekable, an
 * [open] at an arbitrary position only has to fast-forward the counter — there is never a plaintext
 * copy of a track on disk, not even a temporary one.
 */
@UnstableApi
class VaultDataSource(private val vault: VaultFiles) : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var file: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    /** Reused between reads so steady-state playback does not allocate. */
    private var scratch = ByteArray(0)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val id = dataSpec.uri.host
            ?: throw IOException("Malformed vault uri: ${dataSpec.uri}")
        val target = vault.fileFor(id)
        if (!target.exists()) throw IOException("Vault entry missing: $id")

        val handle = RandomAccessFile(target, "r")
        this.file = handle

        val iv = ByteArray(VaultCrypto.IV_LENGTH)
        handle.readFully(iv)

        val plaintextLength = handle.length() - VaultCrypto.IV_LENGTH
        if (dataSpec.position > plaintextLength) {
            throw IOException("Seek past end of $id")
        }

        handle.seek(VaultCrypto.IV_LENGTH + dataSpec.position)
        cipher = VaultCrypto.decryptorAt(iv, dataSpec.position)

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            plaintextLength - dataSpec.position
        } else {
            min(dataSpec.length, plaintextLength - dataSpec.position)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val wanted = min(length.toLong(), bytesRemaining).toInt()
        if (scratch.size < wanted) scratch = ByteArray(wanted)

        val read = file!!.read(scratch, 0, wanted)
        if (read == -1) return C.RESULT_END_OF_INPUT

        // CTR is a stream cipher, so update() emits exactly as many bytes as it consumes.
        val written = cipher!!.update(scratch, 0, read, buffer, offset)
        bytesRemaining -= written
        bytesTransferred(written)
        return written
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cipher = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val vault: VaultFiles) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(vault)
    }
}
