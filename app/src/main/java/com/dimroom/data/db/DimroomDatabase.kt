package com.dimroom.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PhotoEntity::class,
        AlbumEntity::class,
        AlbumPhotoCrossRef::class,
        EditStateEntity::class,
        PresetEntity::class,
        CompositeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class DimroomDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    abstract fun albumDao(): AlbumDao

    abstract fun editDao(): EditDao

    abstract fun presetDao(): PresetDao

    abstract fun compositeDao(): CompositeDao

    companion object {
        const val NAME = "dimroom.db"

        /**
         * Adds HDR support: how a photo came to exist, the stack that groups a merge with its
         * brackets, and the provenance table.
         *
         * Written out properly rather than leaning on the destructive fallback, because a library
         * that loses its album membership and grouping on upgrade is a library the user has to
         * rebuild by hand.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN kind TEXT NOT NULL DEFAULT 'ORIGINAL'")
                db.execSQL("ALTER TABLE photos ADD COLUMN stackId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE photos ADD COLUMN isStackPrimary INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_stackId ON photos (stackId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hdr_merges (
                        mergedPhotoId TEXT NOT NULL,
                        sourcePhotoIds TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        aligned INTEGER NOT NULL,
                        PRIMARY KEY(mergedPhotoId),
                        FOREIGN KEY(mergedPhotoId) REFERENCES photos(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Generalises HDR provenance into one composites table so panorama — and anything after it
         * — records the same facts in the same place rather than growing a table per feature.
         *
         * Rebuild-and-copy rather than `ALTER TABLE ADD COLUMN`, because the new column has no
         * sensible default: every row that already exists is an HDR merge and has to say so.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS composites (
                        mergedPhotoId TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        sourcePhotoIds TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        aligned INTEGER NOT NULL,
                        PRIMARY KEY(mergedPhotoId),
                        FOREIGN KEY(mergedPhotoId) REFERENCES photos(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO composites
                        (mergedPhotoId, kind, sourcePhotoIds, createdAtMs, aligned)
                    SELECT mergedPhotoId, 'HDR_MERGE', sourcePhotoIds, createdAtMs, aligned
                    FROM hdr_merges
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE IF EXISTS hdr_merges")
            }
        }
    }
}
