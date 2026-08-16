package com.dimroom.domain.model

/** A photo in the local library. */
data class Photo(
    val id: String,
    val displayName: String,
    val originalPath: String,
    val thumbnailPath: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val dateTakenMs: Long,
    val hasEdits: Boolean = false,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
}

/** A user-created collection of photos. */
data class Album(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val photoCount: Int = 0,
    val coverPhotoPath: String? = null,
)

/** A named, reusable edit stack. */
data class Preset(
    val id: String,
    val name: String,
    val edits: EditStack,
    val isBuiltIn: Boolean,
    val createdAtMs: Long,
)

/** Library sort orders offered in the grid. */
enum class SortOrder(val displayName: String) {
    DATE_ADDED_DESC("Newest added"),
    DATE_ADDED_ASC("Oldest added"),
    DATE_TAKEN_DESC("Newest captured"),
    DATE_TAKEN_ASC("Oldest captured"),
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
}

/** Options for rendering an edited photo back out to a file. */
data class ExportOptions(
    val quality: Int = 92,
    /** Longest-edge cap in pixels, or null to keep the original resolution. */
    val maxDimension: Int? = null,
) {
    companion object {
        val SIZE_PRESETS: List<Pair<String, Int?>> = listOf(
            "Full resolution" to null,
            "4096 px" to 4096,
            "2048 px" to 2048,
            "1024 px" to 1024,
        )
    }
}
