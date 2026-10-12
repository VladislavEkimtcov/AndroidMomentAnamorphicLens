package io.github.vladislavekimtcov.anamorphic.core

import kotlin.math.roundToInt

/** What to do with a photo. */
enum class Mode {
    /** Desqueeze: stretch a squeezed anamorphic photo back to its real proportions. */
    STRETCH,

    /** Undo a previous [STRETCH] (or any accidental desqueeze). */
    UNSTRETCH,
}

/**
 * Squeeze factor of the anamorphic adapter.
 *
 * Moment sells its classic anamorphic lens as "1.33x". Like every 1.33x anamorphic it is
 * a 4:3 squeeze (4:3 photos become 16:9, 16:9 video becomes 2.37:1), so we use exactly 4/3.
 */
enum class SqueezeFactor(val value: Double, val label: String) {
    X133(4.0 / 3.0, "1.33x"),
    X155(1.55, "1.55x"),
    ;

    companion object {
        /** Finds the factor closest to [value], or null if none is within 1%. */
        fun closestTo(value: Double): SqueezeFactor? =
            entries.minByOrNull { kotlin.math.abs(it.value - value) }
                ?.takeIf { kotlin.math.abs(it.value - value) / it.value < 0.01 }
    }
}

/** An axis of the *stored* pixel buffer, i.e. before any EXIF Orientation is applied. */
enum class Axis(val xmpName: String) {
    WIDTH("width"),
    HEIGHT("height"),
    ;

    fun other(): Axis = if (this == WIDTH) HEIGHT else WIDTH

    companion object {
        fun fromXmpName(name: String?): Axis? = entries.firstOrNull { it.xmpName == name }
    }
}

object Desqueeze {

    /**
     * Picks the axis to scale.
     *
     * The lens squeezes along a fixed direction of the camera *sensor*. With the lens aligned
     * the way Moment intends (flares horizontal when shooting landscape), that is the sensor's
     * long side, so a landscape photo is squeezed horizontally and a portrait photo vertically.
     * Rotating the lens 90 degrees with the hex tool moves the squeeze to the short side.
     *
     * Using the long/short side of the stored pixel buffer makes this independent of whether
     * the camera app rotated the pixels or only wrote an EXIF Orientation tag, because a
     * 90 degree rotation never changes which side is the long one. Square photos fall back to
     * the usual sensor-oriented storage, where the pixel width is the sensor's long side.
     */
    fun axisFor(width: Int, height: Int, rotated: Boolean): Axis {
        val longSide = if (height > width) Axis.HEIGHT else Axis.WIDTH
        return if (rotated) longSide.other() else longSide
    }

    /** Length of a side after stretching by [factor]. */
    fun stretched(length: Int, factor: Double): Int = (length * factor).roundToInt().coerceAtLeast(1)

    /**
     * Length of a side after undoing a stretch by [factor].
     * For any factor > 1 this exactly inverts [stretched]: `unstretched(stretched(n)) == n`.
     */
    fun unstretched(length: Int, factor: Double): Int = (length / factor).roundToInt().coerceAtLeast(1)
}

/** File names for processed copies, so that Stretch and Unstretch undo each other's names too. */
object OutputNames {
    const val STRETCHED_SUFFIX = "_stretched"
    const val UNSTRETCHED_SUFFIX = "_unstretched"

    fun forMode(originalName: String?, mode: Mode, fallbackBase: String = "IMG"): String {
        val name = originalName?.takeIf { it.isNotBlank() } ?: "$fallbackBase.jpg"
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && name.substring(dot + 1).lowercase() in setOf("jpg", "jpeg", "jpe")
        var base = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ".jpg"
        // Pixel motion photos are named "PXL_..._123.MP.jpg"; the output is a still photo.
        if (base.endsWith(".MP", ignoreCase = true)) base = base.dropLast(3)
        base = when (mode) {
            Mode.STRETCH ->
                if (base.endsWith(UNSTRETCHED_SUFFIX)) base.removeSuffix(UNSTRETCHED_SUFFIX) else base + STRETCHED_SUFFIX
            Mode.UNSTRETCH ->
                if (base.endsWith(STRETCHED_SUFFIX)) base.removeSuffix(STRETCHED_SUFFIX) else base + UNSTRETCHED_SUFFIX
        }
        return base + extension
    }
}
