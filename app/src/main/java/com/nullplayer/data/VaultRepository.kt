package com.nullplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.CipherOutputStream

private const val TAG = "VaultRepository"

class VaultRepository(private val context: Context) {

    private val dao = VaultDatabase.get(context).trackDao()
    private val groups = VaultDatabase.get(context).groupDao()
    val files = VaultFiles(context)

    // -- Groups -------------------------------------------------------------------------------

    fun observeGroups(): Flow<List<GroupSummary>> = groups.observeSummaries()

    /**
     * Makes a group with no questions asked. It is named and coloured by its cardinal, and the
     * pencil in the library is there for anyone who wants to change either.
     */
    suspend fun createGroup(): Group = withContext(Dispatchers.IO) {
        val sortIndex = groups.maxSortIndex() + 1
        val cardinal = sortIndex + 1
        Group(
            id = UUID.randomUUID().toString(),
            name = Group.defaultName(cardinal),
            colorArgb = Group.defaultColor(cardinal),
            sortIndex = sortIndex,
            createdAt = System.currentTimeMillis(),
        ).also { groups.insert(it) }
    }

    suspend fun renameGroup(id: String, name: String, colorArgb: Int) = withContext(Dispatchers.IO) {
        groups.byId(id)?.let { groups.update(it.copy(name = name, colorArgb = colorArgb)) }
        Unit
    }

    /**
     * Deleting a group only unfiles its tracks. They stay in the vault, which is the whole point
     * of tags being a view rather than a home.
     */
    suspend fun deleteGroup(id: String) = withContext(Dispatchers.IO) {
        groups.clearGroup(id)
        groups.byId(id)?.let { groups.delete(it) }
        Unit
    }

    /** Which groups every one of [trackIds] already belongs to. */
    suspend fun groupsSharedBy(trackIds: List<String>): Set<String> = withContext(Dispatchers.IO) {
        if (trackIds.isEmpty()) emptySet()
        else groups.groupsSharedBy(trackIds, trackIds.size).toSet()
    }

    suspend fun setTag(trackIds: List<String>, groupId: String, tagged: Boolean) =
        withContext(Dispatchers.IO) {
            trackIds.forEach { trackId ->
                if (tagged) groups.tag(TrackGroup(trackId, groupId))
                else groups.untag(trackId, groupId)
            }
        }

    // -- Tracks -------------------------------------------------------------------------------

    /**
     * Pass a blank group for the whole vault, or [Group.FAVORITES_ID] for everything hearted.
     *
     * The two standing tiles are answered here rather than by tagging their tracks into real
     * groups, which is what lets them exist without a row anyone could rename or delete.
     */
    fun observeTracks(groupId: String): Flow<List<Track>> = when (groupId) {
        Group.VAULT_ID -> dao.observeAll()
        Group.FAVORITES_ID -> dao.observeFavorites()
        else -> dao.observeInGroup(groupId)
    }

    suspend fun tracks(groupId: String): List<Track> = when (groupId) {
        Group.VAULT_ID -> dao.all()
        Group.FAVORITES_ID -> dao.favorites()
        else -> dao.inGroup(groupId)
    }

    /** How many tracks are hearted. The Favorites tile stands or falls on this alone. */
    fun observeFavoriteCount(): Flow<Int> = dao.observeFavoriteCount()

    suspend fun setFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, favorite)
    }

    suspend fun track(id: String): Track? = dao.byId(id)

    /** Bytes on disk for a group, or for the whole vault when the group is blank. */
    suspend fun vaultBytes(groupId: String): Long = withContext(Dispatchers.IO) {
        tracks(groupId).sumOf { files.fileFor(it.id).length() }
    }

    /**
     * Pulls one file in from wherever the user picked it and re-homes it in the vault, encrypted.
     *
     * The original is never referenced again: playback reads the vault copy, so the source can be
     * deleted or the SD card removed without breaking anything.
     */
    suspend fun import(
        source: Uri,
        groupIds: List<String> = emptyList(),
        removeOriginal: Boolean = false,
        fallbackName: String? = null,
    ): Result<Track> =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val destination = files.fileFor(id)
            try {
                val metadata = readMetadata(source, fallbackName)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Not a readable audio file")
                    )

                val iv = ByteArray(VaultCrypto.IV_LENGTH).also { SecureRandom().nextBytes(it) }
                context.contentResolver.openInputStream(source).use { input ->
                    if (input == null) error("Could not open $source")
                    FileOutputStream(destination).use { raw ->
                        raw.write(iv)
                        CipherOutputStream(raw, VaultCrypto.encryptor(iv)).use { encrypted ->
                            input.copyTo(encrypted, DEFAULT_BUFFER_SIZE)
                        }
                    }
                }

                val track = Track(
                    id = id,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    trackNumber = metadata.trackNumber,
                    year = metadata.year,
                    durationMs = metadata.durationMs,
                    addedAt = System.currentTimeMillis(),
                    sortIndex = dao.maxSortIndex() + 1,
                )
                dao.insert(track)
                groupIds.forEach { groups.tag(TrackGroup(id, it)) }

                if (removeOriginal) deleteOriginal(source)
                Result.success(track)
            } catch (t: Throwable) {
                destination.delete()
                Log.w(TAG, "Import failed for $source", t)
                Result.failure(t)
            }
        }

    /**
     * Pulls one file in from a live stream, encrypting it as the bytes arrive.
     *
     * [import] can read a file's tags before it copies a single byte, because it is handed
     * something seekable. A socket is not: the tags cannot be read until the whole file is
     * somewhere, and the only place it is allowed to be is the vault. So the order is inverted —
     * the stream is encrypted straight into its final home, and the tags are read back out of it
     * afterwards through [VaultMediaSource]. Receiving, encrypting and writing all happen at once,
     * nothing is written to storage twice, and no plaintext copy of the file exists at any point.
     *
     * A stream that turns out not to be audio has already been written by the time that is known,
     * so it is deleted again on the way out — the same thing [import] does for a failure, just
     * later in the sequence.
     */
    suspend fun importStream(
        source: InputStream,
        bytes: Long,
        groupIds: List<String> = emptyList(),
        fallbackName: String? = null,
    ): Result<Track> =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val destination = files.fileFor(id)
            try {
                val iv = ByteArray(VaultCrypto.IV_LENGTH).also { SecureRandom().nextBytes(it) }
                FileOutputStream(destination).use { raw ->
                    raw.write(iv)
                    CipherOutputStream(raw, VaultCrypto.encryptor(iv)).use { encrypted ->
                        source.copyExactly(bytes, encrypted)
                    }
                }

                val metadata = readVaultMetadata(destination, fallbackName)
                    ?: throw IllegalArgumentException("Not a readable audio file")

                val track = Track(
                    id = id,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    trackNumber = metadata.trackNumber,
                    year = metadata.year,
                    durationMs = metadata.durationMs,
                    addedAt = System.currentTimeMillis(),
                    sortIndex = dao.maxSortIndex() + 1,
                )
                dao.insert(track)
                groupIds.forEach { groups.tag(TrackGroup(id, it)) }
                Result.success(track)
            } catch (t: Throwable) {
                destination.delete()
                Log.w(TAG, "Streamed import failed", t)
                Result.failure(t)
            }
        }

    // -- Loudness -----------------------------------------------------------------------------

    /**
     * Everything that has never been measured. The sweep that drains this lives in
     * [com.nullplayer.playback.LoudnessScanner], because measuring means decoding and decoding is
     * the audio side's business.
     */
    suspend fun unmeasured(): List<Track> = withContext(Dispatchers.IO) { dao.unmeasured() }

    fun observeUnmeasured(): Flow<List<String>> = dao.observeUnmeasured()

    suspend fun setLoudness(id: String, lufs: Double, peak: Double) = withContext(Dispatchers.IO) {
        dao.setLoudness(id, lufs, peak)
    }

    suspend fun delete(track: Track) = withContext(Dispatchers.IO) {
        files.fileFor(track.id).delete()
        groups.untagEverywhere(track.id)
        dao.delete(track)
    }

    private fun deleteOriginal(source: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, source)
        } catch (t: Throwable) {
            // Plenty of providers refuse this. Not fatal: the vault copy already exists.
            Log.i(TAG, "Provider would not delete the original at $source", t)
        }
    }

    private class AudioMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val trackNumber: Int?,
        val year: String?,
        val durationMs: Long,
    )

    private fun readMetadata(source: Uri, fallbackName: String?): AudioMetadata? =
        tagsFrom(fallbackName, displayName = { displayNameOf(source) }) { openFor(it, source) }

    /** Tags read back out of a file already in the vault, decrypted on the fly. */
    private fun readVaultMetadata(file: File, fallbackName: String?): AudioMetadata? =
        VaultMediaSource(file).use { reader ->
            tagsFrom(fallbackName, displayName = { null }) { it.setDataSource(reader) }
        }

    /**
     * The tag read itself, once something has said where the bytes are.
     *
     * [displayName] is the last resort for a title and is deliberately lazy: it is a
     * `ContentResolver` query, and it is only worth making for a file that turned out to have no
     * title tag of its own.
     */
    private fun tagsFrom(
        fallbackName: String?,
        displayName: () -> String?,
        open: (MediaMetadataRetriever) -> Unit,
    ): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasAudio) return null

            AudioMetadata(
                title = retriever.string(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: fallbackName?.substringBeforeLast('.')
                    ?: displayName(),
                artist = retriever.string(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                // The tag is often "3/12"; only the leading number is interesting.
                trackNumber = retriever.string(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = retriever.string(MediaMetadataRetriever.METADATA_KEY_YEAR),
                durationMs = retriever.string(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read metadata", t)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Points the retriever at a source, by file descriptor first.
     *
     * `setDataSource(Context, Uri)` refuses a good many `content://` URIs — a share from another
     * app, or anything MediaStore serves — with a bare EINVAL. The descriptor comes from the same
     * `ContentResolver` grant the import copy itself relies on, so if the bytes can be read at all
     * they can be read this way. The Context overload stays as the fallback for the odd provider
     * that only answers to it.
     */
    private fun openFor(retriever: MediaMetadataRetriever, source: Uri) {
        val opened = runCatching {
            context.contentResolver.openFileDescriptor(source, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
            } ?: error("Provider returned no descriptor for $source")
        }
        if (opened.isFailure) {
            Log.i(TAG, "Falling back to the Context reader for $source", opened.exceptionOrNull())
            retriever.setDataSource(context, source)
        }
    }

    /** Falls back to the picked filename when a file carries no title tag. */
    private fun displayNameOf(source: Uri): String? = try {
        context.contentResolver.query(source, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.substringBeforeLast('.')
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun MediaMetadataRetriever.string(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Moves exactly [bytes] into [sink], and refuses to be short-changed.
     *
     * The source is a keep-alive socket. Reading past the end of the body would eat the head of
     * the next request; stopping short would leave this body's tail to be read as one. Either
     * mistake corrupts the connection rather than just this file, so the count is a contract and
     * a stream that ends early is an error.
     */
    private fun InputStream.copyExactly(bytes: Long, sink: OutputStream) {
        require(bytes >= 0) { "byte count must not be negative" }
        val buffer = ByteArray(STREAM_BUFFER)
        var remaining = bytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) error("Stream ended $remaining bytes early")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }

    private companion object {
        /**
         * Eight times [DEFAULT_BUFFER_SIZE], which is what a plain `copyTo` would use.
         *
         * Every buffer costs a socket read, a cipher update that allocates its own output array,
         * and a write. At 8 KB a 20 MB upload pays for all three two and a half thousand times
         * over; a larger buffer is the cheapest thing available here.
         */
        const val STREAM_BUFFER = 64 * 1024
    }
}
