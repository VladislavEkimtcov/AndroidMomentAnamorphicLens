package io.github.vladislavekimtcov.anamorphic.core

import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifThumbnailDirectory
import com.drew.metadata.exif.GpsDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ExifPatcherTest {

    private val thumb = JpegFile.parse(TestImages.jpeg(200, 100, 80)).let {
        Thumbnail(JpegFile.assemble(emptyList(), it.image), 200, 100)
    }

    private fun exif(littleEndian: Boolean, atEnd: Boolean): ByteArray {
        val e = TestExif(littleEndian)
        val oldThumb = JpegFile.parse(TestImages.jpeg(160, 120, 80)).let { JpegFile.assemble(emptyList(), it.image) }
        return e.build(
            ifd0 = listOf(e.ascii(0x010F, "Google"), e.short(0x0112, 6), e.short(0x0100, 4032), e.short(0x0101, 3024)),
            exif = listOf(e.short(0xA002, 4032), e.short(0xA003, 3024), e.undefined(0x927C, Fixtures.MAKER_NOTE)),
            gps = listOf(e.ascii(0x0001, "N"), e.rationals(0x0002, 40 to 1, 6 to 1, 3000 to 100)),
            thumbnail = oldThumb,
            thumbnailAtEnd = atEnd,
        )
    }

    /** Reads the payload back with an independent EXIF implementation. */
    private fun read(payload: ByteArray) = JpegMetadataReader.readMetadata(
        ByteArrayInputStream(JpegFile.assemble(listOf(JpegSegment(JpegSegment.APP1, payload)), JpegFile.parse(TestImages.jpeg(8, 8)).image)),
    )

    @Test
    fun patchesDimensionsAndThumbnailInBothByteOrders() {
        for (littleEndian in listOf(true, false)) for (atEnd in listOf(true, false)) {
            val original = exif(littleEndian, atEnd)
            val patcher = ExifPatcher(original)
            assertEquals(6, patcher.orientation)
            assertEquals(160 to 120, patcher.thumbnailSize)

            val patched = patcher.patched(70_000, 3024) { thumb }
            val metadata = read(patched)
            val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            assertEquals("Google", ifd0.getString(ExifIFD0Directory.TAG_MAKE))
            assertEquals(6, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION))
            assertEquals(70_000, ifd0.getInt(ExifIFD0Directory.TAG_IMAGE_WIDTH)) // SHORT widened to LONG
            assertEquals(70_000, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH))
            assertEquals(3024, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT))
            assertArrayEquals(Fixtures.MAKER_NOTE, sub.getByteArray(ExifSubIFDDirectory.TAG_MAKERNOTE))
            assertEquals(3, metadata.getFirstDirectoryOfType(GpsDirectory::class.java).getRationalArray(GpsDirectory.TAG_LATITUDE).size)

            val again = ExifPatcher(patched)
            assertArrayEquals(thumb.jpeg, again.thumbnailBytes())
            assertEquals(200 to 100, again.thumbnailSize)
            assertNotNull(metadata.getFirstDirectoryOfType(ExifThumbnailDirectory::class.java))
            if (atEnd) assertTrue(patched.size < original.size + thumb.jpeg.size)
        }
    }

    @Test
    fun dropsThumbnailWhenNoneFits() {
        val patched = ExifPatcher(exif(littleEndian = true, atEnd = true)).patched(5376, 3024) { null }
        val patcher = ExifPatcher(patched)
        assertFalse(patcher.hasThumbnailIfd)
        assertNull(patcher.thumbnailBytes())
        assertEquals(5376, read(patched).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java).getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH))
    }

    @Test
    fun respectsTheSegmentSizeLimit() {
        var offeredMax = 0
        ExifPatcher(exif(littleEndian = true, atEnd = true)).patched(10, 10) { max ->
            offeredMax = max
            Thumbnail(ByteArray(max + 1), 1, 1)
        }.also { assertFalse(ExifPatcher(it).hasThumbnailIfd) }
        assertTrue(offeredMax in 1..JpegSegment.MAX_PAYLOAD)
    }

    @Test(expected = ExifFormatException::class)
    fun rejectsGarbage() {
        ExifPatcher(JpegSegment.EXIF_ID + ByteArray(20) { 0x55 })
    }
}
