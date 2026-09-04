package com.nullplayer.data

import android.content.Context
import java.io.File

/**
 * Locates the encrypted audio blobs.
 *
 * The vault sits in internal storage, which is not indexed by MediaStore, is not visible to file
 * managers or other apps, and is excluded from backup by the manifest. A track that goes in here
 * genuinely stops existing as far as the rest of the phone is concerned.
 */
class VaultFiles(context: Context) {

    private val root = File(context.filesDir, "vault").apply { mkdirs() }

    fun fileFor(id: String): File = File(root, "$id.bin")

    fun totalBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** Scheme for [VaultDataSource]. `nullvault://<track id>`. */
        const val SCHEME = "nullvault"

        fun uriFor(id: String): String = "$SCHEME://$id"
    }
}
