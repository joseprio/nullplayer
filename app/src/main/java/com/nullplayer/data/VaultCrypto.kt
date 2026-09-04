package com.nullplayer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Every byte of audio in the vault is encrypted with a key that lives in the Android Keystore and
 * never leaves it. AES-CTR is used rather than GCM or CBC because it is a stream cipher: the
 * keystream for any byte offset can be produced without touching the bytes before it, which is what
 * lets the player seek inside a track without decrypting it from the start.
 */
object VaultCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "nullplayer.vault.v1"
    private const val TRANSFORMATION = "AES/CTR/NoPadding"

    /** AES block size, and therefore both the IV length and the counter stride. */
    const val IV_LENGTH = 16

    private val keyLock = Any()

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // We supply our own IV so that the same IV can be re-derived for random access.
                .setRandomizedEncryptionRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    fun encryptor(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(), IvParameterSpec(iv))
        }

    /**
     * A cipher positioned at [plaintextOffset] bytes into the stream.
     *
     * The counter is advanced by whole blocks, then the remainder of the partial block is burned off
     * so the very next [Cipher.update] lines up with the requested byte.
     */
    fun decryptorAt(iv: ByteArray, plaintextOffset: Long): Cipher {
        require(plaintextOffset >= 0) { "offset must not be negative" }
        val blocks = plaintextOffset / IV_LENGTH
        val remainder = (plaintextOffset % IV_LENGTH).toInt()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), IvParameterSpec(counterPlus(iv, blocks)))
        }
        if (remainder > 0) cipher.update(ByteArray(remainder))
        return cipher
    }

    /** Treats [iv] as a big-endian 128-bit integer and returns `iv + blocks`, wrapping like AES-CTR. */
    internal fun counterPlus(iv: ByteArray, blocks: Long): ByteArray {
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
        val out = iv.copyOf()
        var carry = blocks
        var i = out.size - 1
        while (i >= 0 && carry != 0L) {
            val sum = (out[i].toLong() and 0xFF) + (carry and 0xFF)
            out[i] = (sum and 0xFF).toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i--
        }
        return out
    }
}
