package com.dimroom.data.db

import androidx.room.TypeConverter
import com.dimroom.domain.model.PhotoKind

class DbConverters {

    @TypeConverter
    fun fromPhotoKind(kind: PhotoKind): String = kind.name

    /** Unknown values decay to [PhotoKind.ORIGINAL] so a downgrade cannot make rows unreadable. */
    @TypeConverter
    fun toPhotoKind(value: String?): PhotoKind =
        PhotoKind.entries.firstOrNull { it.name == value } ?: PhotoKind.ORIGINAL
}
