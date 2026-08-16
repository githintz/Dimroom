package com.dimroom.domain.model

/**
 * Starter presets seeded on first launch. Ids are stable so re-seeding updates the recipe in place
 * instead of duplicating entries, and so a sidecar's `presetId` keeps resolving across installs.
 */
object BuiltInPresets {

    val ALL: List<Preset> = listOf(
        preset(
            id = "builtin.bw_classic",
            name = "B&W Classic",
            edits = EditStack(
                light = EditStack.Light(contrast = 22f, highlights = -12f, shadows = 14f, blacks = -10f),
                color = EditStack.Color(saturation = -100f),
                effects = EditStack.Effects(clarity = 18f, grain = 12f),
            ),
        ),
        preset(
            id = "builtin.warm_film",
            name = "Warm Film",
            edits = EditStack(
                light = EditStack.Light(
                    exposure = 0.15f,
                    contrast = -8f,
                    highlights = -25f,
                    shadows = 30f,
                    blacks = 12f,
                ),
                color = EditStack.Color(temperature = 18f, tint = 6f, vibrance = 14f, saturation = -6f),
                hsl = EditStack.Hsl().with(
                    ColorBand.ORANGE,
                    EditStack.BandAdjustment(ColorBand.ORANGE, hue = -8f, saturation = 12f, luminance = 8f),
                ).with(
                    ColorBand.GREEN,
                    EditStack.BandAdjustment(ColorBand.GREEN, hue = 14f, saturation = -18f),
                ),
                effects = EditStack.Effects(clarity = -6f, vignette = -18f, grain = 22f),
            ),
        ),
        preset(
            id = "builtin.cool_matte",
            name = "Cool Matte",
            edits = EditStack(
                light = EditStack.Light(contrast = -14f, highlights = -18f, shadows = 22f, blacks = 22f),
                color = EditStack.Color(temperature = -16f, tint = -4f, vibrance = -8f, saturation = -12f),
                hsl = EditStack.Hsl().with(
                    ColorBand.BLUE,
                    EditStack.BandAdjustment(ColorBand.BLUE, saturation = 14f, luminance = 10f),
                ),
                effects = EditStack.Effects(clarity = -10f, dehaze = -12f, vignette = -8f),
            ),
        ),
        preset(
            id = "builtin.punchy_landscape",
            name = "Punchy Landscape",
            edits = EditStack(
                light = EditStack.Light(contrast = 18f, highlights = -34f, shadows = 26f, whites = 12f, blacks = -12f),
                color = EditStack.Color(vibrance = 28f, saturation = 6f),
                hsl = EditStack.Hsl().with(
                    ColorBand.BLUE,
                    EditStack.BandAdjustment(ColorBand.BLUE, saturation = 16f, luminance = -12f),
                ).with(
                    ColorBand.GREEN,
                    EditStack.BandAdjustment(ColorBand.GREEN, hue = -10f, saturation = 10f),
                ),
                effects = EditStack.Effects(clarity = 26f, dehaze = 20f, vignette = -12f),
            ),
        ),
        preset(
            id = "builtin.soft_portrait",
            name = "Soft Portrait",
            edits = EditStack(
                light = EditStack.Light(exposure = 0.2f, contrast = -10f, highlights = -20f, shadows = 18f),
                color = EditStack.Color(temperature = 8f, tint = 4f, vibrance = 10f, saturation = -4f),
                hsl = EditStack.Hsl().with(
                    ColorBand.ORANGE,
                    EditStack.BandAdjustment(ColorBand.ORANGE, saturation = -6f, luminance = 14f),
                ),
                effects = EditStack.Effects(clarity = -18f, vignette = -10f),
            ),
        ),
        preset(
            id = "builtin.high_key_bw",
            name = "High Key B&W",
            edits = EditStack(
                light = EditStack.Light(
                    exposure = 0.45f,
                    contrast = 12f,
                    highlights = 10f,
                    shadows = 34f,
                    whites = 20f,
                    blacks = 16f,
                ),
                color = EditStack.Color(saturation = -100f),
                effects = EditStack.Effects(clarity = 8f, grain = 8f),
            ),
        ),
    )

    private fun preset(id: String, name: String, edits: EditStack) = Preset(
        id = id,
        name = name,
        edits = edits,
        isBuiltIn = true,
        createdAtMs = 0L,
    )
}
