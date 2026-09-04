package com.nullplayer.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tag: a named, coloured group a track can be filed under.
 *
 * There is one vault holding everything; groups are a view onto it, not a home for anything. A
 * track belongs to as many as it likes, or to none at all, and deleting a group only unfiles the
 * tracks — it never removes them from the vault.
 */
@Entity(tableName = "groups")
data class Group(
    @PrimaryKey val id: String,
    val name: String,
    /** Packed ARGB, drawn as the tile's fill. */
    val colorArgb: Int,
    val sortIndex: Int,
    val createdAt: Long,
) {
    companion object {
        /** What a group is called when nobody has named it: its cardinal, one-based. */
        fun defaultName(cardinal: Int): String = "Group #$cardinal"

        /** The colour a new group gets, walking the palette so neighbours differ. */
        fun defaultColor(cardinal: Int): Int =
            PALETTE[(cardinal - 1).coerceAtLeast(0) % PALETTE.size]

        /** The presets offered in the colour picker. */
        val PALETTE = listOf(
            0xFF43B061.toInt(), // green
            0xFF3F82C8.toInt(), // blue
            0xFF8A63D2.toInt(), // violet
            0xFFD1568F.toInt(), // pink
            0xFFC8563F.toInt(), // rust
            0xFFE0A32E.toInt(), // amber
            0xFF2FA8A0.toInt(), // teal
            0xFF8B8D94.toInt(), // slate
        )

        /**
         * The whole vault, which is not a group and has no row. Light grey so it reads as the
         * neutral "everything" rather than as one tag among the coloured ones.
         */
        const val VAULT_ID = ""
        const val VAULT_NAME = "Vault"
        val VAULT_COLOR = 0xFFD4D6DB.toInt()
    }
}

/** Which tracks are in which group. A track may appear in any number of rows, or none. */
@Entity(
    tableName = "track_groups",
    primaryKeys = ["trackId", "groupId"],
    indices = [Index("groupId")],
)
data class TrackGroup(
    val trackId: String,
    val groupId: String,
)

/** A group plus how much is filed under it, which is all a tile ever shows. */
data class GroupSummary(
    @Embedded val group: Group,
    val itemCount: Int,
) {
    val id: String get() = group.id
    val name: String get() = group.name
    val colorArgb: Int get() = group.colorArgb
}
