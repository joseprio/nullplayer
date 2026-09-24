package com.nullplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Track::class, Group::class, TrackGroup::class, TrackBeats::class],
    version = 8,
    exportSchema = true,
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile
        private var instance: VaultDatabase? = null

        /**
         * Version 2 introduced vaults. Everything that already existed belongs to one shelf, so
         * the migration creates it and stamps every existing row with its id — destructive
         * migration is not an option when the rows are the only index into an encrypted store
         * that has no export path.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS vaults (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO vaults (id, name, colorArgb, sortIndex, createdAt) " +
                        "VALUES ('default', 'Vault #1', ?, 0, ?)",
                    arrayOf(Group.PALETTE.first(), System.currentTimeMillis()),
                )
                db.execSQL("ALTER TABLE tracks ADD COLUMN vaultId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_vaultId ON tracks (vaultId)")
            }
        }

        /**
         * Version 3 turns vaults inside out: one vault holds everything, and the shelves become
         * groups a track can be in any number of.
         *
         * Each old vault becomes a group with the same tracks filed under it, so nothing is lost
         * — except where there was only ever one vault, which was just "everything" under another
         * name and would migrate to a group duplicating the Vault tile.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_groups (
                        trackId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        PRIMARY KEY(trackId, groupId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_groups_groupId " +
                        "ON track_groups (groupId)"
                )

                db.execSQL(
                    """
                    INSERT INTO groups (id, name, colorArgb, sortIndex, createdAt)
                    SELECT id, name, colorArgb, sortIndex, createdAt FROM vaults
                    WHERE (SELECT COUNT(*) FROM vaults) > 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO track_groups (trackId, groupId)
                    SELECT id, vaultId FROM tracks WHERE vaultId IN (SELECT id FROM groups)
                    """.trimIndent()
                )

                // SQLite cannot drop a column in this version, so the table is rebuilt without it.
                db.execSQL(
                    """
                    CREATE TABLE tracks_v3 (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT,
                        artist TEXT,
                        album TEXT,
                        trackNumber INTEGER,
                        year TEXT,
                        durationMs INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        sortIndex INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO tracks_v3
                    SELECT id, title, artist, album, trackNumber, year, durationMs, addedAt,
                           sortIndex
                    FROM tracks
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE tracks")
                db.execSQL("ALTER TABLE tracks_v3 RENAME TO tracks")
                db.execSQL("DROP TABLE vaults")
            }
        }

        /**
         * Version 4 gives every track somewhere to record how loud it is.
         *
         * Both columns are left null, which is exactly what they mean: nothing has been measured
         * yet. The sweep behind volume normalisation reads that null as its work queue, so an
         * existing vault backfills itself the first time the setting is switched on rather than
         * needing anything done to it here.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN loudnessLufs REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN peakAmplitude REAL")
            }
        }

        /**
         * Version 5 gives every track a heart.
         *
         * Defaulted to 0, which is what an existing vault should read as: nothing has been marked
         * yet, and the Favorites tile stays out of the ribbon until two tracks have been.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Version 6 gives tracks somewhere to keep their haptic envelope.
         *
         * A table rather than a column, and empty to begin with: the analysis sweep reads a
         * missing row as its work queue, the same way it reads a null loudness.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS haptics (
                        trackId TEXT NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL,
                        points BLOB NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Version 7 takes the haptic envelopes out again: the platform's haptic generator works
         * them out from the audio as it plays, so there is nothing left to store.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS haptics")
            }
        }

        /**
         * Version 8 gives tracks somewhere to keep their beats. Empty to begin with, like the
         * haptics table was: a missing row is the sweep's cue to analyse the track.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS beats (
                        trackId TEXT NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        beats BLOB NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    // Lives under /data/data/<pkg>/databases, which no other app can read.
                    "vault.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
