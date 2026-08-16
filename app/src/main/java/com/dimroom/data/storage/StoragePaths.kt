package com.dimroom.data.storage

/**
 * Folder-safe form of an album name.
 *
 * Album names become directory names in the `/Originals/{albumName}` layout, and that layout has to
 * survive being mirrored to backends with far stricter naming rules than a local filesystem — so
 * this is deliberately conservative rather than merely legal on Android. Path separators and `..`
 * are neutralised here as well as in the provider's own path resolution.
 */
fun String.sanitizedAsFolder(): String =
    trim()
        .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        .replace(Regex("^\\.+"), "_")
        .trim()
        .ifEmpty { "Album" }
