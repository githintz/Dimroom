package com.dimroom.domain.model

/** How a photo came to exist. */
enum class PhotoKind {
    /** Imported from the device exactly as the camera wrote it. */
    ORIGINAL,

    /** Produced by fusing a bracket of exposures. */
    HDR_MERGE,

    /** Produced by stitching a sweep of overlapping frames. */
    PANORAMA,
}

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
    val kind: PhotoKind = PhotoKind.ORIGINAL,
    /** Non-null when this photo represents a group of photos collapsed into one tile. */
    val stackId: String? = null,
    /** Number of photos in the stack, or 0 when this photo stands alone. */
    val stackSize: Int = 0,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    /** True when this tile stands in for several photos and can be ungrouped. */
    val isStack: Boolean get() = stackId != null && stackSize > 1
}

/** What should happen to the source brackets once a merge finishes. */
enum class MergeMode(val displayName: String, val description: String) {
    SEPARATE(
        "Keep photos separate",
        "The merged HDR is added to your library alongside the originals, which stay exactly where they are.",
    ),
    GROUPED(
        "Group as one HDR",
        "The merged HDR takes one place in your library and the originals are tucked inside it. You can ungroup at any time.",
    ),
}

/** Everything the library needs to describe a merge the user asked for. */
data class HdrMergeRequest(
    val sourcePhotoIds: List<String>,
    val name: String,
    val mode: MergeMode,
    val alignFrames: Boolean = true,
)

/**
 * A panorama the user asked for. [sourcePhotoIds] are in sweep order — the stitcher only matches
 * adjacent pairs, so the order the user selected them in is load-bearing.
 */
data class PanoramaRequest(
    val sourcePhotoIds: List<String>,
    val name: String,
    val mode: MergeMode,
)

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
