package io.github.vladislavekimtcov.anamorphic.core

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pixels as 32-bit words of four independent 8-bit channels. The channel order (and the color
 * space) is whatever the codec uses; resampling treats all four lanes the same way.
 * [colorInfo] lets a codec carry its color space through processing untouched.
 */
class RawImage(val width: Int, val height: Int, val pixels: IntArray, val colorInfo: Any? = null) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad image size" }
    }
}

/** Lanczos-3 resampling, one axis at a time. */
object Resampler {
    private const val LOBES = 3.0
    private const val SHIFT = 14
    private const val ONE = 1 shl SHIFT

    private class Kernel(val taps: Int, val indices: IntArray, val weights: IntArray)

    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -LOBES || x >= LOBES) return 0.0
        val px = PI * x
        return LOBES * sin(px) * sin(px / LOBES) / (px * px)
    }

    private fun kernel(srcSize: Int, dstSize: Int): Kernel {
        val scale = dstSize.toDouble() / srcSize
        // When shrinking, widen the filter so it also acts as the anti-aliasing low-pass.
        val stretch = if (scale < 1.0) 1.0 / scale else 1.0
        val support = LOBES * stretch
        val taps = ceil(support * 2).toInt() + 1
        val indices = IntArray(dstSize * taps)
        val weights = IntArray(dstSize * taps)
        val raw = DoubleArray(taps)
        for (i in 0 until dstSize) {
            val center = (i + 0.5) / scale - 0.5
            val first = floor(center - support).toInt() + 1
            var sum = 0.0
            for (t in 0 until taps) {
                val w = lanczos((first + t - center) / stretch)
                raw[t] = w
                sum += w
            }
            var fixedSum = 0
            var largest = 0
            for (t in 0 until taps) {
                val w = Math.round(raw[t] / sum * ONE).toInt()
                weights[i * taps + t] = w
                indices[i * taps + t] = min(max(first + t, 0), srcSize - 1)
                fixedSum += w
                if (w > weights[i * taps + largest]) largest = t
            }
            // Make the weights sum to exactly ONE so flat areas stay exactly flat.
            weights[i * taps + largest] += ONE - fixedSum
        }
        return Kernel(taps, indices, weights)
    }

    /** Scales [image] along [axis] to [newLength] pixels; the other axis is unchanged. */
    fun resample(image: RawImage, axis: Axis, newLength: Int): RawImage {
        require(newLength > 0)
        return when (axis) {
            Axis.WIDTH -> if (newLength == image.width) image else resampleWidth(image, newLength)
            Axis.HEIGHT -> if (newLength == image.height) image else resampleHeight(image, newLength)
        }
    }

    /** Uniformly scales [image] to [width] x [height] (used for thumbnails). */
    fun resize(image: RawImage, width: Int, height: Int): RawImage {
        // Box-reduce big downscales first; Lanczos then only has a small step left to do.
        var current = image
        val boxX = max(1, current.width / (width * 2))
        val boxY = max(1, current.height / (height * 2))
        if (boxX > 1 || boxY > 1) current = boxReduce(current, boxX, boxY)
        current = resample(current, Axis.WIDTH, width)
        return resample(current, Axis.HEIGHT, height)
    }

    private inline fun bands(rows: Int, crossinline body: (from: Int, to: Int) -> Unit) {
        val bandSize = max(8, rows / (Runtime.getRuntime().availableProcessors() * 4))
        val bandCount = (rows + bandSize - 1) / bandSize
        IntStream.range(0, bandCount).parallel().forEach { band ->
            body(band * bandSize, min(rows, (band + 1) * bandSize))
        }
    }

    private fun clamp(acc: Int): Int {
        val v = (acc + (ONE shr 1)) shr SHIFT
        return if (v < 0) 0 else if (v > 255) 255 else v
    }

    private fun resampleWidth(src: RawImage, newWidth: Int): RawImage {
        val k = kernel(src.width, newWidth)
        val out = IntArray(newWidth * src.height)
        val srcPixels = src.pixels
        bands(src.height) { from, to ->
            for (y in from until to) {
                val rowIn = y * src.width
                val rowOut = y * newWidth
                for (x in 0 until newWidth) {
                    var c0 = 0; var c1 = 0; var c2 = 0; var c3 = 0
                    val base = x * k.taps
                    for (t in 0 until k.taps) {
                        val w = k.weights[base + t]
                        if (w == 0) continue
                        val p = srcPixels[rowIn + k.indices[base + t]]
                        c0 += (p and 0xFF) * w
                        c1 += ((p ushr 8) and 0xFF) * w
                        c2 += ((p ushr 16) and 0xFF) * w
                        c3 += (p ushr 24) * w
                    }
                    out[rowOut + x] = clamp(c0) or (clamp(c1) shl 8) or (clamp(c2) shl 16) or (clamp(c3) shl 24)
                }
            }
        }
        return RawImage(newWidth, src.height, out, src.colorInfo)
    }

    private fun resampleHeight(src: RawImage, newHeight: Int): RawImage {
        val k = kernel(src.height, newHeight)
        val width = src.width
        val out = IntArray(width * newHeight)
        val srcPixels = src.pixels
        bands(newHeight) { from, to ->
            val a0 = IntArray(width); val a1 = IntArray(width); val a2 = IntArray(width); val a3 = IntArray(width)
            for (y in from until to) {
                a0.fill(0); a1.fill(0); a2.fill(0); a3.fill(0)
                val base = y * k.taps
                for (t in 0 until k.taps) {
                    val w = k.weights[base + t]
                    if (w == 0) continue
                    val rowIn = k.indices[base + t] * width
                    for (x in 0 until width) {
                        val p = srcPixels[rowIn + x]
                        a0[x] += (p and 0xFF) * w
                        a1[x] += ((p ushr 8) and 0xFF) * w
                        a2[x] += ((p ushr 16) and 0xFF) * w
                        a3[x] += (p ushr 24) * w
                    }
                }
                val rowOut = y * width
                for (x in 0 until width) {
                    out[rowOut + x] = clamp(a0[x]) or (clamp(a1[x]) shl 8) or (clamp(a2[x]) shl 16) or (clamp(a3[x]) shl 24)
                }
            }
        }
        return RawImage(width, newHeight, out, src.colorInfo)
    }

    private fun boxReduce(src: RawImage, fx: Int, fy: Int): RawImage {
        val w = src.width / fx
        val h = src.height / fy
        val out = IntArray(w * h)
        val n = fx * fy
        bands(h) { from, to ->
            for (y in from until to) {
                for (x in 0 until w) {
                    var c0 = 0; var c1 = 0; var c2 = 0; var c3 = 0
                    for (dy in 0 until fy) {
                        val row = (y * fy + dy) * src.width + x * fx
                        for (dx in 0 until fx) {
                            val p = src.pixels[row + dx]
                            c0 += p and 0xFF
                            c1 += (p ushr 8) and 0xFF
                            c2 += (p ushr 16) and 0xFF
                            c3 += p ushr 24
                        }
                    }
                    out[y * w + x] = (c0 / n) or ((c1 / n) shl 8) or ((c2 / n) shl 16) or ((c3 / n) shl 24)
                }
            }
        }
        return RawImage(w, h, out, src.colorInfo)
    }
}
