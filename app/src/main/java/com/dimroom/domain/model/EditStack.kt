package com.dimroom.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The non-destructive edit stack for a single photo.
 *
 * This type is the in-memory form of the sidecar JSON that [com.dimroom.data.storage.StorageProvider]
 * persists next to the original (`/Edits/{photoId}.json`). It is intentionally a plain, flat,
 * defaulted data structure so that:
 *
 *  * the exact same JSON a local install writes is what a cloud folder would eventually hold, and
 *  * older sidecars keep deserializing when new adjustments are added (every field has a default).
 *
 * Slider conventions follow Lightroom: [Light.exposure] is in stops (EV), every other adjustment is
 * a `-100..100` percentage (or `0..100` for one-sided effects such as grain). Keeping the stored
 * values in user-facing units — rather than pre-normalised shader units — is what makes the format
 * portable: the GPU pipeline normalises at draw time in
 * [com.dimroom.editor.gl.EditUniforms].
 */
@Serializable
data class EditStack(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("light") val light: Light = Light(),
    @SerialName("color") val color: Color = Color(),
    @SerialName("hsl") val hsl: Hsl = Hsl(),
    @SerialName("effects") val effects: Effects = Effects(),
    @SerialName("geometry") val geometry: Geometry = Geometry(),
    /** Id of the preset this stack was last stamped from, or null when hand-tuned. */
    @SerialName("presetId") val presetId: String? = null,
    @SerialName("presetName") val presetName: String? = null,
) {

    /** True when nothing has been changed from the original capture. */
    val isIdentity: Boolean
        get() = light == Light() &&
            color == Color() &&
            hsl == Hsl() &&
            effects == Effects() &&
            geometry == Geometry()

    /**
     * Copies only the tonal/colour parts of another stack, leaving geometry alone.
     * Presets deliberately never carry crop or rotation — those are per-photo framing decisions.
     */
    fun applyPreset(preset: EditStack, presetId: String?, presetName: String?): EditStack = copy(
        light = preset.light,
        color = preset.color,
        hsl = preset.hsl,
        effects = preset.effects,
        presetId = presetId,
        presetName = presetName,
    )

    /** Strips geometry so the stack can be stored as a reusable preset. */
    fun asPresetPayload(): EditStack = copy(
        geometry = Geometry(),
        presetId = null,
        presetName = null,
    )

    @Serializable
    data class Light(
        /** Exposure in stops, -5..5. */
        @SerialName("exposure") val exposure: Float = 0f,
        @SerialName("contrast") val contrast: Float = 0f,
        @SerialName("highlights") val highlights: Float = 0f,
        @SerialName("shadows") val shadows: Float = 0f,
        @SerialName("whites") val whites: Float = 0f,
        @SerialName("blacks") val blacks: Float = 0f,
    )

    @Serializable
    data class Color(
        /** Warm/cool shift, -100..100. Negative is cooler (blue), positive warmer (amber). */
        @SerialName("temperature") val temperature: Float = 0f,
        /** Green/magenta shift, -100..100. */
        @SerialName("tint") val tint: Float = 0f,
        @SerialName("vibrance") val vibrance: Float = 0f,
        @SerialName("saturation") val saturation: Float = 0f,
    )

    /** Per-band hue/saturation/luminance, one entry per [ColorBand]. */
    @Serializable
    data class Hsl(
        @SerialName("bands") val bands: List<BandAdjustment> = ColorBand.entries.map { BandAdjustment(it) },
    ) {
        operator fun get(band: ColorBand): BandAdjustment =
            bands.firstOrNull { it.band == band } ?: BandAdjustment(band)

        fun with(band: ColorBand, adjustment: BandAdjustment): Hsl {
            val next = ColorBand.entries.map { if (it == band) adjustment else this[it] }
            return copy(bands = next)
        }
    }

    @Serializable
    data class BandAdjustment(
        @SerialName("band") val band: ColorBand,
        /** Hue rotation within the band, -100..100 (maps to roughly ±30°). */
        @SerialName("hue") val hue: Float = 0f,
        @SerialName("saturation") val saturation: Float = 0f,
        @SerialName("luminance") val luminance: Float = 0f,
    ) {
        val isIdentity: Boolean get() = hue == 0f && saturation == 0f && luminance == 0f
    }

    @Serializable
    data class Effects(
        @SerialName("clarity") val clarity: Float = 0f,
        @SerialName("dehaze") val dehaze: Float = 0f,
        /** Negative darkens the corners, positive brightens them. */
        @SerialName("vignette") val vignette: Float = 0f,
        /** 0..100. */
        @SerialName("grain") val grain: Float = 0f,
    )

    /**
     * Crop is stored as normalised edges of the *unrotated* original, so the same sidecar
     * survives a re-download of the original at a different resolution.
     */
    @Serializable
    data class Geometry(
        @SerialName("cropLeft") val cropLeft: Float = 0f,
        @SerialName("cropTop") val cropTop: Float = 0f,
        @SerialName("cropRight") val cropRight: Float = 1f,
        @SerialName("cropBottom") val cropBottom: Float = 1f,
        /** Coarse rotation in 90° steps: 0, 90, 180 or 270. */
        @SerialName("rotationDegrees") val rotationDegrees: Int = 0,
        /** Fine straightening in degrees, -45..45. */
        @SerialName("straightenDegrees") val straightenDegrees: Float = 0f,
        @SerialName("flipHorizontal") val flipHorizontal: Boolean = false,
        @SerialName("flipVertical") val flipVertical: Boolean = false,
        @SerialName("aspectRatio") val aspectRatio: AspectRatioPreset = AspectRatioPreset.ORIGINAL,
    ) {
        val cropWidth: Float get() = (cropRight - cropLeft).coerceAtLeast(MIN_CROP)
        val cropHeight: Float get() = (cropBottom - cropTop).coerceAtLeast(MIN_CROP)

        /** Clamps the crop rect into the unit square while keeping it non-degenerate. */
        fun normalised(): Geometry {
            val left = cropLeft.coerceIn(0f, 1f - MIN_CROP)
            val top = cropTop.coerceIn(0f, 1f - MIN_CROP)
            val right = cropRight.coerceIn(left + MIN_CROP, 1f)
            val bottom = cropBottom.coerceIn(top + MIN_CROP, 1f)
            val rotation = ((rotationDegrees % 360) + 360) % 360
            return copy(
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom,
                rotationDegrees = rotation - (rotation % 90),
                straightenDegrees = straightenDegrees.coerceIn(-45f, 45f),
            )
        }

        companion object {
            const val MIN_CROP = 0.05f
        }
    }

    companion object {
        /** Bump when the on-disk sidecar shape changes incompatibly. */
        const val SCHEMA_VERSION = 1
    }
}

/** The eight Lightroom-style HSL bands. */
@Serializable
enum class ColorBand(val displayName: String, val centerHueDegrees: Float) {
    @SerialName("red")
    RED("Red", 0f),

    @SerialName("orange")
    ORANGE("Orange", 30f),

    @SerialName("yellow")
    YELLOW("Yellow", 60f),

    @SerialName("green")
    GREEN("Green", 120f),

    @SerialName("aqua")
    AQUA("Aqua", 180f),

    @SerialName("blue")
    BLUE("Blue", 240f),

    @SerialName("purple")
    PURPLE("Purple", 280f),

    @SerialName("magenta")
    MAGENTA("Magenta", 320f),
}

/** Crop aspect presets offered in the geometry panel. */
@Serializable
enum class AspectRatioPreset(val displayName: String, val ratio: Float?) {
    @SerialName("original")
    ORIGINAL("Original", null),

    @SerialName("free")
    FREE("Free", null),

    @SerialName("square")
    SQUARE("1:1", 1f),

    @SerialName("r45")
    RATIO_4_5("4:5", 4f / 5f),

    @SerialName("r34")
    RATIO_3_4("3:4", 3f / 4f),

    @SerialName("r23")
    RATIO_2_3("2:3", 2f / 3f),

    @SerialName("r169")
    RATIO_16_9("16:9", 16f / 9f),

    @SerialName("r32")
    RATIO_3_2("3:2", 3f / 2f),
}
