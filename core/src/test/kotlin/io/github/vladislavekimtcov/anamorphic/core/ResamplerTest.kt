package io.github.vladislavekimtcov.anamorphic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResamplerTest {

    private fun channel(p: Int, c: Int) = (p ushr (8 * c)) and 0xFF

    @Test
    fun flatImageStaysExactlyFlat() {
        val color = 0x80C0_4020.toInt()
        val image = RawImage(97, 61, IntArray(97 * 61) { color })
        for (axis in Axis.entries) {
            for (length in listOf(30, 61, 97, 129, 150)) {
                val out = Resampler.resample(image, axis, length)
                assertTrue(out.pixels.all { it == color })
            }
        }
    }

    @Test
    fun onlyTheChosenAxisChanges() {
        val image = TestImages.pattern(120, 90)
        val wide = Resampler.resample(image, Axis.WIDTH, 160)
        assertEquals(160, wide.width)
        assertEquals(90, wide.height)
        val tall = Resampler.resample(image, Axis.HEIGHT, 120)
        assertEquals(120, tall.width)
        assertEquals(120, tall.height)
    }

    @Test
    fun stretchThenUnstretchIsNearlyLossless() {
        val image = TestImages.pattern(300, 200)
        for (axis in Axis.entries) {
            for (factor in SqueezeFactor.entries) {
                val length = if (axis == Axis.WIDTH) image.width else image.height
                val stretched = Resampler.resample(image, axis, Desqueeze.stretched(length, factor.value))
                val back = Resampler.resample(stretched, axis, length)
                var totalError = 0L
                for (i in image.pixels.indices) for (c in 0 until 4) {
                    totalError += abs(channel(image.pixels[i], c) - channel(back.pixels[i], c))
                }
                val meanError = totalError.toDouble() / (image.pixels.size * 4)
                assertTrue("mean error $meanError for $axis $factor", meanError < 1.5)
            }
        }
    }

    @Test
    fun resizeMakesThumbnails() {
        val image = TestImages.pattern(1600, 900)
        val thumb = Resampler.resize(image, 512, 288)
        assertEquals(512, thumb.width)
        assertEquals(288, thumb.height)
    }
}
