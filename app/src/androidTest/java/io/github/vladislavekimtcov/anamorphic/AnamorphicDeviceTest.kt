package io.github.vladislavekimtcov.anamorphic

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vladislavekimtcov.anamorphic.core.AnamorphicProcessor
import io.github.vladislavekimtcov.anamorphic.core.JpegFile
import io.github.vladislavekimtcov.anamorphic.core.Mode
import io.github.vladislavekimtcov.anamorphic.core.ProcessOptions
import io.github.vladislavekimtcov.anamorphic.core.ProcessResult
import io.github.vladislavekimtcov.anamorphic.core.SqueezeFactor
import io.github.vladislavekimtcov.anamorphic.core.XmpEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** Runs the real Android codec and MediaStore on an emulator. */
@RunWith(AndroidJUnit4::class)
class AnamorphicDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val created = ArrayList<Uri>()
    private val folder = "Pictures/AnamorphicTest/"

    private fun fixture(): ByteArray = instrumentation.context.assets.open("fixture_camera.jpg").use { it.readBytes() }

    private val stretch = ProcessOptions(Mode.STRETCH, SqueezeFactor.X133, rotated = false)

    @After
    fun cleanUp() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        // Also remove anything the share flow created in the test folder.
        resolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(folder),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                runCatching { resolver.delete(Uri.withAppendedPath(uri, c.getLong(0).toString()), null, null) }
            }
        }
        Settings(context).replaceOriginal = true
    }

    @Test
    fun stretchKeepsMetadataWithThePlatformCodec() {
        val result = AnamorphicProcessor(BitmapJpegCodec()).process(fixture(), stretch) as ProcessResult.Success
        assertEquals(533, result.width)
        assertEquals(300, result.height)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(result.jpeg, 0, result.jpeg.size, bounds)
        assertEquals(533, bounds.outWidth)
        assertEquals(300, bounds.outHeight)

        val exif = ExifInterface(ByteArrayInputStream(result.jpeg))
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Pixel 3", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("2020:10:11 18:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(533, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(300, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
        val latLong = exif.latLong
        assertNotNull(latLong)
        assertEquals(40.1083, latLong!![0], 1e-3)
        assertEquals(-88.2292, latLong[1], 1e-3)
        val thumbnail = exif.thumbnailBitmap
        assertNotNull(thumbnail)
        assertEquals(533.0 / 300.0, thumbnail!!.width.toDouble() / thumbnail.height, 0.05)

        val xmp = XmpEditor.fromSegment(JpegFile.parse(result.jpeg).metadata.first { it.isXmp })
        assertEquals(400, XmpEditor.readMarker(xmp)!!.originalWidth)
    }

    @Test
    fun unstretchRestoresTheOriginalSize() {
        val processor = AnamorphicProcessor(BitmapJpegCodec())
        val stretched = processor.process(fixture(), stretch.copy(factor = SqueezeFactor.X155)) as ProcessResult.Success
        assertEquals(620, stretched.width)
        val restored = processor.process(stretched.jpeg, stretch.copy(mode = Mode.UNSTRETCH)) as ProcessResult.Success
        assertEquals(400, restored.width)
        assertEquals(300, restored.height)
    }

    @Test
    fun sharingToStretchSavesACopyNextToTheOriginal() {
        // A photo in MediaStore, as a camera app would leave it.
        val original = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "PXL_TEST_123.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            },
        )!!
        created += original
        resolver.openOutputStream(original, "w")!!.use { it.write(fixture()) }

        Settings(context).replaceOriginal = false
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClassName(context.packageName, "${context.packageName}.StretchShareTarget")
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, original)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

        val output = waitForPhoto("PXL_TEST_123_stretched.jpg")
        assertNotNull("The stretched copy never appeared in MediaStore", output)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(output!!)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(533, bounds.outWidth)
        assertEquals(300, bounds.outHeight)
        val exif = resolver.openInputStream(output)!!.use { ExifInterface(it) }
        assertEquals("Pixel 3", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertTrue(resolver.openInputStream(original)!!.use { it.readBytes().isNotEmpty() })
    }

    private fun waitForPhoto(name: String): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(name, folder),
                null,
            )?.use { c ->
                if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString()).also { created += it }
            }
            Thread.sleep(500)
        }
        return null
    }
}
