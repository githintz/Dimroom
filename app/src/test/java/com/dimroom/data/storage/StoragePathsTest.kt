package com.dimroom.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoragePathsTest {

    @Test
    fun `keeps ordinary album names intact`() {
        assertEquals("Iceland 2024", "Iceland 2024".sanitizedAsFolder())
        assertEquals("family-photos_v2", "family-photos_v2".sanitizedAsFolder())
    }

    @Test
    fun `neutralises path separators and traversal`() {
        assertFalse("../etc".sanitizedAsFolder().contains(".."))
        assertFalse("a/b".sanitizedAsFolder().contains("/"))
        assertFalse("a\\b".sanitizedAsFolder().contains("\\"))
    }

    @Test
    fun `falls back for names with nothing usable left`() {
        assertEquals("Album", "".sanitizedAsFolder())
        assertEquals("Album", "   ".sanitizedAsFolder())
    }

    @Test
    fun `builds the documented storage layout`() {
        assertEquals("Originals/Iceland/photo-1.jpg", StorageProvider.originalPath("Iceland", "photo-1"))
        assertEquals("Edits/photo-1.json", StorageProvider.sidecarPath("photo-1"))
        assertEquals("Previews/photo-1_thumb.jpg", StorageProvider.thumbnailPath("photo-1"))
    }
}
