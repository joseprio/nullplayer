package com.nullplayer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every byte of audio in the vault is encrypted with a 256-bit data key, and that data key is
 * itself encrypted with a key that lives in the Android Keystore and never leaves it. Two keys
 * rather than one, because they are asked to do very different jobs.
 *
 * The keystore key is asked to do almost nothing: unwrap thirty-two bytes, once, when the app
 * starts. That is what a keystore key is for. The data key is asked to decipher whole albums,
 * seek around inside them, and serve several readers at once — the player, the tag pass, the
 * loudness sweep and the web server can all be mid-file simultaneously — and for that it is an
 * ordinary in-memory key handed to ordinary software AES, which on any device this app runs on
 * means BoringSSL and the ARMv8 crypto instructions.
 *
 * The previous arrangement used the keystore key for the audio itself, and the trouble was never
 * throughput: it was that a cipher initialised with a keystore key holds an *operation* open in
 * the keystore daemon, and a device has on the order of sixteen for everything running on it. A
 * loudness sweep seeking through a file could hold every one, and the symptom surfaced somewhere
 * unrelated — the player, unable to open its next track, sat buffering until a slot came free.
 * A software key has no such shared resource behind it, so the limit is not raised here, it is
 * gone.
 *
 * What that costs: the data key is plaintext in this process's memory for as long as the app is
 * running, so anyone able to read the heap gets the whole vault rather than just whichever track
 * is open. At rest nothing changes — the vault files are meaningless without [KEY_FILE], and that
 * is meaningless without a keystore key that cannot be copied off the device.
 *
 * AES-CTR is used for the audio rather than GCM or CBC because it is a stream cipher: the
 * keystream for any byte offset can be produced without touching the bytes before it, which is
 * what lets the player seek inside a track without decrypting it from the start. The data key
 * itself is wrapped with GCM, which is authenticated and needs no seeking.
 */
object VaultCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val MASTER_ALIAS = "nullplayer.vault.master.v1"
    private const val WRAP_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val BULK_TRANSFORMATION = "AES/CTR/NoPadding"

    private const val KEY_FILE = "vault.key"
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /** AES block size, and therefore both the IV length and the counter stride. */
    const val IV_LENGTH = 16

    private val keyLock = Any()

    @Volatile
    private var dataKey: SecretKey? = null

    /**
     * Points the vault at the file holding its wrapped data key, and unwraps it.
     *
     * Called from `NullPlayerApp` for the same reason [com.nullplayer.playback.PlaybackGate] is:
     * the player, the web server and the import path all reach for the vault, and any of the three
     * can be the first to wake. Doing it once at start also means the single keystore round trip
     * is paid somewhere it cannot be mistaken for a track being slow to open.
     *
     * A missing or unreadable key file is treated as a vault that does not exist yet and a fresh
     * data key is written. There is deliberately no recovery path: if that file is lost, so is
     * every track in the vault, which is the property the vault is for.
     */
    fun bind(context: Context) {
        val file = File(context.filesDir, KEY_FILE)
        synchronized(keyLock) {
            dataKey = loadDataKey(file) ?: createDataKey(file)
        }
    }

    private fun dataKey(): SecretKey =
        dataKey ?: error("VaultCrypto.bind() has not been called")

    private fun loadDataKey(file: File): SecretKey? {
        if (!file.exists()) return null
        return runCatching {
            val blob = file.readBytes()
            val ivLength = blob[0].toInt() and 0xFF
            val cipher = Cipher.getInstance(WRAP_TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    masterKey(),
                    GCMParameterSpec(GCM_TAG_BITS, blob, 1, ivLength),
                )
            }
            val raw = cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength)
            SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES) as SecretKey
        }.getOrNull()
    }

    private fun createDataKey(file: File): SecretKey {
        val raw = ByteArray(KEY_BITS / 8).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, masterKey())
        }
        val wrapped = cipher.doFinal(raw)
        val iv = cipher.iv

        // Written aside and moved into place, so a kill mid-write cannot leave behind a key file
        // that is half of one key and half of another — which would read as a vault of noise.
        val scratch = File(file.parentFile, "$KEY_FILE.new")
        scratch.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(wrapped)
        }
        file.delete()
        if (!scratch.renameTo(file)) {
            scratch.delete()
            error("Could not write the vault key file")
        }
        return SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES)
    }

    /** The keystore key that wraps the data key. Fetched rather than held: it is touched once. */
    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return (store.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateMasterKey()
    }

    private fun generateMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Randomised encryption is left at its default of required: this key wraps one
                // small blob and never has to reproduce an IV, so the keystore is the right thing
                // to be choosing them.
                .build()
        )
        return generator.generateKey()
    }

    fun encryptor(iv: ByteArray): Cipher =
        Cipher.getInstance(BULK_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, dataKey(), IvParameterSpec(iv))
        }

    /**
     * A cipher positioned at [plaintextOffset] bytes into the stream.
     *
     * The counter is advanced by whole blocks, then the remainder of the partial block is burned
     * off so the very next [Cipher.update] lines up with the requested byte.
     *
     * Callers used to have to hand these back, and to re-initialise one they already held rather
     * than derive a second. Neither is asked of a software cipher: it holds nothing but its own
     * key schedule, and dropping the reference is the whole of releasing it.
     */
    fun decryptorAt(iv: ByteArray, plaintextOffset: Long): Cipher {
        require(plaintextOffset >= 0) { "offset must not be negative" }
        val blocks = plaintextOffset / IV_LENGTH
        val remainder = (plaintextOffset % IV_LENGTH).toInt()

        val cipher = Cipher.getInstance(BULK_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, dataKey(), IvParameterSpec(counterPlus(iv, blocks)))
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
