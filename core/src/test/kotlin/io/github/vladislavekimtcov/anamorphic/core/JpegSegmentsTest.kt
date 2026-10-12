package io.github.vladislavekimtcov.anamorphic.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegSegmentsTest {

    @Test
    fun splitsMetadataImageAndTrailer() {
        val bytes = Fixtures.cameraJpeg()
        val file = JpegFile.parse(bytes)
        assertTrue(file.metadata[0].isExif)
        assertTrue(file.metadata[1].isXmp)
        assertTrue(file.metadata[2].isExtendedXmp)
        assertTrue(file.metadata[3].isMpf)
        assertEquals(JpegSegment.COM, file.metadata[4].marker)
        assertArrayEquals(Fixtures.TRAILER, file.trailer)
        assertEquals(400 to 300, file.frameSize)
        assertArrayEquals(bytes, file.toByteArray() + file.trailer)
    }

    @Test
    fun plainJpegHasNoTrailer() {
        val bytes = TestImages.jpeg(33, 17)
        val file = JpegFile.parse(bytes)
        assertEquals(0, file.trailer.size)
        assertEquals(33 to 17, file.frameSize)
        assertTrue(file.metadata.single().isApp0Jfif)
        assertArrayEquals(bytes, file.toByteArray())
    }

    @Test
    fun detectsNonJpeg() {
        assertFalse(JpegFile.isJpeg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())))
        assertTrue(JpegFile.isJpeg(TestImages.jpeg(8, 8)))
    }
}
