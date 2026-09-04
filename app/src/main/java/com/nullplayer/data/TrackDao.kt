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

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): Track?

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM tracks")
    suspend fun maxSortIndex(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Delete
    suspend fun delete(track: Track)
}
