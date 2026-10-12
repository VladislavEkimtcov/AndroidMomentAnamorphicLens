package io.github.vladislavekimtcov.anamorphic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesqueezeTest {

    @Test
    fun defaultTwistStretchesTheLongSide() {
        assertEquals(Axis.WIDTH, Desqueeze.axisFor(4032, 3024, rotated = false)) // landscape pixels
        assertEquals(Axis.HEIGHT, Desqueeze.axisFor(3024, 4032, rotated = false)) // pre-rotated portrait
        assertEquals(Axis.WIDTH, Desqueeze.axisFor(3024, 3024, rotated = false))
    }

    @Test
    fun rotatedTwistStretchesTheShortSide() {
        assertEquals(Axis.HEIGHT, Desqueeze.axisFor(4032, 3024, rotated = true))
        assertEquals(Axis.WIDTH, Desqueeze.axisFor(3024, 4032, rotated = true))
        assertEquals(Axis.HEIGHT, Desqueeze.axisFor(3024, 3024, rotated = true))
    }

    @Test
    fun pixelPhotoBecomesSixteenByNine() {
        assertEquals(5376, Desqueeze.stretched(4032, SqueezeFactor.X133.value))
        assertEquals(4032, Desqueeze.unstretched(5376, SqueezeFactor.X133.value))
        assertEquals(6250, Desqueeze.stretched(4032, SqueezeFactor.X155.value))
    }

    @Test
    fun unstretchExactlyInvertsStretch() {
        for (factor in SqueezeFactor.entries) {
            for (n in 1..12_000) {
                assertEquals(n, Desqueeze.unstretched(Desqueeze.stretched(n, factor.value), factor.value))
            }
        }
    }

    @Test
    fun closestFactor() {
        assertEquals(SqueezeFactor.X133, SqueezeFactor.closestTo(1.333333))
        assertEquals(SqueezeFactor.X133, SqueezeFactor.closestTo(1.33))
        assertEquals(SqueezeFactor.X155, SqueezeFactor.closestTo(1.55))
        assertNull(SqueezeFactor.closestTo(2.0))
    }

    @Test
    fun outputNamesUndoEachOther() {
        assertEquals("PXL_20201011_183000123_stretched.jpg", OutputNames.forMode("PXL_20201011_183000123.jpg", Mode.STRETCH))
        assertEquals("PXL_20201011_183000123.jpg", OutputNames.forMode("PXL_20201011_183000123_stretched.jpg", Mode.UNSTRETCH))
        assertEquals("IMG_1_unstretched.jpg", OutputNames.forMode("IMG_1.jpg", Mode.UNSTRETCH))
        assertEquals("IMG_1.JPG", OutputNames.forMode("IMG_1_unstretched.JPG", Mode.STRETCH))
        assertEquals("PXL_1_stretched.jpg", OutputNames.forMode("PXL_1.MP.jpg", Mode.STRETCH))
        assertEquals("photo_stretched.jpg", OutputNames.forMode("photo", Mode.STRETCH))
        assertEquals("IMG_stretched.jpg", OutputNames.forMode(null, Mode.STRETCH))
    }
}
