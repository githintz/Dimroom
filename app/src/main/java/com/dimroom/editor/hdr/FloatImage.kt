package com.dimroom.editor.hdr

/**
 * A plain floating-point image buffer, deliberately free of any Android type.
 *
 * The HDR maths lives on this rather than on `Bitmap` so the whole fusion and alignment pipeline
 * can be exercised by JVM unit tests — the parts most likely to be subtly wrong are also the parts
 * hardest to eyeball on a device.
 *
 * Samples are interleaved by channel and normally held in the 0..1 range.
 */
class FloatImage(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: FloatArray = FloatArray(width * height * channels),
) {

    init {
        require(width > 0 && height > 0) { "Image must be non-empty, got ${width}x$height" }
        require(channels > 0) { "Image needs at least one channel" }
        require(data.size == width * height * channels) {
            "Buffer of ${data.size} does not match ${width}x${height}x$channels"
        }
    }

    val pixelCount: Int get() = width * height

    /** Offset of pixel ([x], [y]) channel 0. */
    fun offset(x: Int, y: Int): Int = (y * width + x) * channels

    operator fun get(x: Int, y: Int, channel: Int): Float = data[offset(x, y) + channel]

    operator fun set(x: Int, y: Int, channel: Int, value: Float) {
        data[offset(x, y) + channel] = value
    }

    /** Samples with edge clamping, which is what the pyramid filters need at the borders. */
    fun clamped(x: Int, y: Int, channel: Int): Float =
        data[offset(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) + channel]

    fun emptyLike(channels: Int = this.channels): FloatImage = FloatImage(width, height, channels)

    fun copy(): FloatImage = FloatImage(width, height, channels, data.copyOf())

    fun clampInPlace(min: Float = 0f, max: Float = 1f): FloatImage {
        for (i in data.indices) data[i] = data[i].coerceIn(min, max)
        return this
    }

    fun sameSizeAs(other: FloatImage): Boolean = width == other.width && height == other.height
}
