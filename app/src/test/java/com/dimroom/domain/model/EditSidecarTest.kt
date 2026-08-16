package com.dimroom.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidecar format is the contract every storage backend shares, so these tests pin down the
 * things a future cloud provider will rely on: stable keys, forward compatibility, and defaults.
 */
class EditSidecarTest {

    @Test
    fun `round trips a full edit stack`() {
        val stack = EditStack(
            light = EditStack.Light(exposure = 1.25f, contrast = 30f, shadows = -12f),
            color = EditStack.Color(temperature = 18f, vibrance = 40f),
            hsl = EditStack.Hsl().with(
                ColorBand.BLUE,
                EditStack.BandAdjustment(ColorBand.BLUE, hue = 10f, saturation = -20f, luminance = 5f),
            ),
            effects = EditStack.Effects(clarity = 15f, grain = 30f),
            geometry = EditStack.Geometry(
                cropLeft = 0.1f,
                cropTop = 0.2f,
                cropRight = 0.9f,
                cropBottom = 0.8f,
                rotationDegrees = 90,
                straightenDegrees = -3.5f,
                aspectRatio = AspectRatioPreset.SQUARE,
            ),
        )

        val decoded = EditSerialization.decodeStack(EditSerialization.encodeStack(stack))

        assertEquals(stack, decoded)
        assertEquals(-20f, decoded.hsl[ColorBand.BLUE].saturation, 0f)
    }

    @Test
    fun `sidecar envelope keeps photo identity`() {
        val sidecar = EditSidecar(
            photoId = "abc-123",
            originalFileName = "IMG_0042.jpg",
            updatedAt = 1_700_000_000_000L,
            edits = EditStack(light = EditStack.Light(exposure = -0.5f)),
        )

        val decoded = EditSerialization.decodeSidecar(EditSerialization.encodeSidecar(sidecar))

        assertNotNull(decoded)
        assertEquals("abc-123", decoded!!.photoId)
        assertEquals("IMG_0042.jpg", decoded.originalFileName)
        assertEquals(-0.5f, decoded.edits.light.exposure, 0f)
        assertEquals(EditStack.SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun `unknown keys from a newer version are ignored`() {
        val futureJson = """
            {
              "schemaVersion": 99,
              "photoId": "future",
              "updatedAt": 1,
              "someBrandNewSection": { "whatever": 1 },
              "edits": { "light": { "exposure": 2.0, "newSlider": 5 } }
            }
        """.trimIndent()

        val decoded = EditSerialization.decodeSidecar(futureJson)

        assertNotNull(decoded)
        assertEquals("future", decoded!!.photoId)
        assertEquals(2.0f, decoded.edits.light.exposure, 0f)
    }

    @Test
    fun `corrupt sidecar decodes to null instead of throwing`() {
        assertNull(EditSerialization.decodeSidecar("{ this is not json"))
    }

    @Test
    fun `missing sections fall back to neutral defaults`() {
        val minimal = """{ "photoId": "p1" }"""

        val decoded = EditSerialization.decodeSidecar(minimal)

        assertNotNull(decoded)
        assertTrue(decoded!!.edits.isIdentity)
        assertEquals(ColorBand.entries.size, decoded.edits.hsl.bands.size)
    }

    @Test
    fun `preset payload drops geometry but keeps tone`() {
        val stack = EditStack(
            light = EditStack.Light(contrast = 25f),
            geometry = EditStack.Geometry(cropLeft = 0.25f, rotationDegrees = 180),
            presetId = "some-preset",
        )

        val payload = stack.asPresetPayload()

        assertEquals(EditStack.Geometry(), payload.geometry)
        assertEquals(25f, payload.light.contrast, 0f)
        assertNull(payload.presetId)
    }

    @Test
    fun `applying a preset leaves the photo's own framing alone`() {
        val framed = EditStack(geometry = EditStack.Geometry(cropLeft = 0.3f, rotationDegrees = 270))
        val preset = EditStack(color = EditStack.Color(saturation = -100f))

        val result = framed.applyPreset(preset, "bw", "B&W")

        assertEquals(0.3f, result.geometry.cropLeft, 0f)
        assertEquals(270, result.geometry.rotationDegrees)
        assertEquals(-100f, result.color.saturation, 0f)
        assertEquals("bw", result.presetId)
    }
}
