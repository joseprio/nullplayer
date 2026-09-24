package com.nullplayer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    /** Everything in the vault. */
    @Query("SELECT * FROM tracks ORDER BY sortIndex ASC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY sortIndex ASC")
    suspend fun all(): List<Track>

    /**
     * One group's tracks, in vault order.
     *
     * Ordering by the track's own index rather than by when it was tagged keeps a group in the
     * same order as the vault it is a view onto.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN track_groups tg ON tg.trackId = t.id
        WHERE tg.groupId = :groupId
        ORDER BY t.sortIndex ASC
        """
    )
    fun observeInGroup(groupId: String): Flow<List<Track>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN track_groups tg ON tg.trackId = t.id
        WHERE tg.groupId = :groupId
        ORDER BY t.sortIndex ASC
        """
    )
    suspend fun inGroup(groupId: String): List<Track>

    /** Everything hearted, in vault order — the Favorites tile's queue. */
    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY sortIndex ASC")
    fun observeFavorites(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY sortIndex ASC")
    suspend fun favorites(): List<Track>

    /** Watched on its own, because the tile appears and disappears on this count alone. */
    @Query("SELECT COUNT(*) FROM tracks WHERE favorite = 1")
    fun observeFavoriteCount(): Flow<Int>

    @Query("UPDATE tracks SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): Track?

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM tracks")
    suspend fun maxSortIndex(): Int

    /**
     * Everything still waiting to be measured for loudness, oldest arrival first.
     *
     * The whole list rather than a page of it: the sweep skips anything that failed to decode this
     * session, and a page could be filled entirely with those while measurable tracks sat behind
     * it.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE loudnessLufs IS NULL
        ORDER BY addedAt ASC
        """
    )
    suspend fun unmeasured(): List<Track>

    /**
     * The same queue, watched.
     *
     * Ids rather than a count, because the sweep has to be able to discount the ones it has
     * already found it cannot read — a count would leave the screen reporting work that is never
     * going to happen.
     */
    @Query(
        """
        SELECT id FROM tracks
        WHERE loudnessLufs IS NULL
        """
    )
    fun observeUnmeasured(): Flow<List<String>>

    @Query("UPDATE tracks SET loudnessLufs = :lufs, peakAmplitude = :peak WHERE id = :id")
    suspend fun setLoudness(id: String, lufs: Double, peak: Double)

    /**
     * Everything the sweep still has to decode for either reason: no loudness yet, or no beats
     * from this [version] of the analysis. Oldest arrival first, and whole, as [unmeasured] is.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE loudnessLufs IS NULL
           OR id NOT IN (SELECT trackId FROM beats WHERE version = :version)
        ORDER BY addedAt ASC
        """
    )
    suspend fun unanalysed(version: Int): List<Track>

    /** The same queue, watched, as ids for the same reason as [observeUnmeasured]. */
    @Query(
        """
        SELECT id FROM tracks
        WHERE loudnessLufs IS NULL
           OR id NOT IN (SELECT trackId FROM beats WHERE version = :version)
        """
    )
    fun observeUnanalysed(version: Int): Flow<List<String>>

    @Query("SELECT * FROM beats WHERE trackId = :id")
    suspend fun beats(id: String): TrackBeats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setBeats(beats: TrackBeats)

    @Query("DELETE FROM beats WHERE trackId = :id")
    suspend fun deleteBeats(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Delete
    suspend fun delete(track: Track)
}
