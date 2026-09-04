package com.nullplayer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    /**
     * Every group with a live count of what is filed under it. One query rather than a count per
     * row, so the ribbon cannot show numbers that disagree with each other.
     */
    @Query(
        """
        SELECT g.*, (SELECT COUNT(*) FROM track_groups tg WHERE tg.groupId = g.id) AS itemCount
        FROM groups g
        ORDER BY g.sortIndex ASC
        """
    )
    fun observeSummaries(): Flow<List<GroupSummary>>

    @Query("SELECT * FROM groups ORDER BY sortIndex ASC")
    suspend fun all(): List<Group>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun byId(id: String): Group?

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM groups")
    suspend fun maxSortIndex(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: Group)

    @Update
    suspend fun update(group: Group)

    @Delete
    suspend fun delete(group: Group)

    // -- Membership ---------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun tag(membership: TrackGroup)

    @Query("DELETE FROM track_groups WHERE trackId = :trackId AND groupId = :groupId")
    suspend fun untag(trackId: String, groupId: String)

    @Query("DELETE FROM track_groups WHERE trackId = :trackId")
    suspend fun untagEverywhere(trackId: String)

    @Query("DELETE FROM track_groups WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("SELECT groupId FROM track_groups WHERE trackId = :trackId")
    suspend fun groupsFor(trackId: String): List<String>

    /** The groups every one of [trackIds] already belongs to. */
    @Query(
        """
        SELECT groupId FROM track_groups
        WHERE trackId IN (:trackIds)
        GROUP BY groupId
        HAVING COUNT(*) = :count
        """
    )
    suspend fun groupsSharedBy(trackIds: List<String>, count: Int): List<String>
}
