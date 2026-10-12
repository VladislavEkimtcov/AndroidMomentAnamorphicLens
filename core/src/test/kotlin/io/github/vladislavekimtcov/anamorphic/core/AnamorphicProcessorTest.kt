package io.github.vladislavekimtcov.anamorphic.core

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.jpeg.JpegCommentDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AnamorphicProcessorTest {

    private val processor = AnamorphicProcessor(ImageIoCodec())
    private val stretch = ProcessOptions(Mode.STRETCH, SqueezeFactor.X133, rotated = false)
    private val unstretch = stretch.copy(mode = Mode.UNSTRETCH)

    private fun success(result: ProcessResult) = result as ProcessResult.Success

    @Test
    fun stretchesAndKeepsMetadata() {
        val input = Fixtures.cameraJpeg(400, 300, orientation = 6)
        val result = success(processor.process(input, stretch))
        assertEquals(533, result.width)
        assertEquals(300, result.height)
        assertEquals(Axis.WIDTH, result.axis)

        val out = JpegFile.parse(result.jpeg)
        assertEquals(533 to 300, out.frameSize)
        assertEquals(0, out.trailer.size)
        assertTrue(out.metadata.none { it.isExtendedXmp || it.isMpf })
        assertTrue(out.metadata.first().isExif)

        val metadata = JpegMetadataReader.readMetadata(ByteArrayInputStream(result.jpeg))
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        assertEquals("Pixel 3", ifd0.getString(ExifIFD0Directory.TAG_MODEL))
        assertEquals(6, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION))
        assertEquals("2020:10:11 18:30:00", sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
        assertEquals(533, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH))
        assertEquals(300, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT))
        assertArrayEquals(Fixtures.MAKER_NOTE, sub.getByteArray(ExifSubIFDDirectory.TAG_MAKERNOTE))
        val location = metadata.getFirstDirectoryOfType(GpsDirectory::class.java).geoLocation
        assertEquals(40.1083, location.latitude, 1e-3)
        assertEquals(-88.2292, location.longitude, 1e-3)
        assertEquals("kept comment", metadata.getFirstDirectoryOfType(JpegCommentDirectory::class.java).getString(JpegCommentDirectory.TAG_COMMENT))

        // The thumbnail now has the stretched proportions.
        val (tw, th) = ExifPatcher(out.metadata.first().payload).thumbnailSize!!
        assertEquals(533.0 / 300.0, tw.toDouble() / th, 0.02)

        // XMP lost the motion photo but gained the marker.
        val xmp = XmpEditor.fromSegment(out.metadata.first { it.isXmp })
        assertFalse(xmp.contains("MotionPhoto"))
        assertEquals(StretchMarker(4.0 / 3.0, Axis.WIDTH, 400, 300).axis, XmpEditor.readMarker(xmp)!!.axis)

        // Everything decodes.
        assertEquals(533, ImageIoCodec().decode(result.jpeg).width)
    }

    @Test
    fun refusesToStretchTwice() {
        val once = success(processor.process(Fixtures.cameraJpeg(), stretch))
        assertSame(ProcessResult.AlreadyStretched, processor.process(once.jpeg, stretch))
    }

    @Test
    fun unstretchRestoresTheOriginalSizeAndRemovesTheMarker() {
        for (factor in SqueezeFactor.entries) for (rotated in listOf(false, true)) {
            for ((w, h) in listOf(401 to 300, 300 to 401, 400 to 300)) {
                val options = stretch.copy(factor = factor, rotated = rotated)
                val stretched = success(processor.process(Fixtures.cameraJpeg(w, h), options))
                // Unstretch must not depend on the current settings when the marker is there.
                val restored = success(processor.process(stretched.jpeg, unstretch.copy(rotated = !rotated)))
                assertEquals("$factor $rotated $w x $h", w to h, restored.width to restored.height)
                val out = JpegFile.parse(restored.jpeg)
                assertNull(out.metadata.firstOrNull { it.isXmp }?.let { XmpEditor.readMarker(XmpEditor.fromSegment(it)) })
                val sub = JpegMetadataReader.readMetadata(ByteArrayInputStream(restored.jpeg))
                    .getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                assertEquals(w, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH))
                // And it can be stretched again.
                assertTrue(processor.process(restored.jpeg, options) is ProcessResult.Success)
            }
        }
    }

    @Test
    fun rotatedLensStretchesTheShortSide() {
        val result = success(processor.process(Fixtures.cameraJpeg(300, 400), stretch.copy(rotated = true)))
        assertEquals(400 to 400, result.width to result.height)
        assertEquals(Axis.WIDTH, result.axis)
    }

    @Test
    fun unstretchWithoutMarkerUsesSettings() {
        val plain = TestImages.jpeg(533, 300)
        val result = success(processor.process(plain, unstretch))
        assertEquals(400 to 300, result.width to result.height)
        // A file without any metadata gets a marker-free result.
        val out = JpegFile.parse(result.jpeg)
        assertTrue(out.metadata.none { it.isExif })
    }

    @Test
    fun plainJpegGetsMarkerOnly() {
        val result = success(processor.process(TestImages.jpeg(64, 48), stretch))
        val out = JpegFile.parse(result.jpeg)
        assertEquals(85 to 48, out.frameSize)
        val xmp = XmpEditor.fromSegment(out.metadata.single { it.isXmp })
        assertEquals(64, XmpEditor.readMarker(xmp)!!.originalWidth)
    }

    @Test(expected = JpegFormatException::class)
    fun rejectsNonJpeg() {
        processor.process("GIF89a".toByteArray(), stretch)
    }
}
