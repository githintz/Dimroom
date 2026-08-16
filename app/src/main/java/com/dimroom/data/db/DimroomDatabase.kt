package com.dimroom.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PhotoEntity::class,
        AlbumEntity::class,
        AlbumPhotoCrossRef::class,
        EditStateEntity::class,
        PresetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DimroomDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    abstract fun albumDao(): AlbumDao

    abstract fun editDao(): EditDao

    abstract fun presetDao(): PresetDao

    companion object {
        const val NAME = "dimroom.db"
    }
}
